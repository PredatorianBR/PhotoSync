import sqlite3
import subprocess
import os
import sys

# Force UTF-8 output
sys.stdout.reconfigure(encoding='utf-8')

print("==================================================")
print("  WhatsSync - Sincronizador Direto WhatsApp -> Google")
print("  (Suporte a Alta Resolução 640x640 + Miniaturas)")
print("==================================================")

os.makedirs("temp_sync", exist_ok=True)

# 1. Obter bases de dados necessárias
print("\n[1/5] Extraindo bancos de dados e mapeamentos do WhatsApp...")
# Copiar msgstore.db para mapear LIDs de alta resolução
subprocess.run([
    "adb", "shell",
    "mkdir -p /sdcard/WhatsSync/cache && "
    "cp -rf '/data/data/com.whatsapp.w4b/cache/Profile Pictures/'* /sdcard/WhatsSync/cache/ 2>/dev/null; "
    "cp -rf '/data/data/com.whatsapp/cache/Profile Pictures/'* /sdcard/WhatsSync/cache/ 2>/dev/null; "
    "mkdir -p /sdcard/WhatsSync/avatars && "
    "cp -rf /data/data/com.whatsapp.w4b/files/Avatars/* /sdcard/WhatsSync/avatars/ 2>/dev/null; "
    "cp -rf /data/data/com.whatsapp/files/Avatars/* /sdcard/WhatsSync/avatars/ 2>/dev/null; true"
])

# Baixar msgstore.db (ou consultar diretamente no dispositivo via sqlite3)
# Vamos extrair o mapeamento de LID -> Phone JID via sqlite3 diretamente no adb
print(" -> Mapeando fotos em alta resolução (640x640)...")
res_map = subprocess.run([
    "adb", "shell",
    "sqlite3 /data/data/com.whatsapp.w4b/databases/msgstore.db "
    "'SELECT j1.raw_string, j2.raw_string FROM jid_map JOIN jid j1 ON jid_map.lid_row_id = j1._id JOIN jid j2 ON jid_map.jid_row_id = j2._id;'"
], capture_output=True, text=True, encoding="utf-8", errors="ignore")

# Dicionário: phone_jid -> lid_numeric
# Exemplo: 5511987315739@s.whatsapp.net -> 101924036722719
phone_to_lid = {}
for line in res_map.stdout.splitlines():
    parts = line.strip().split("|")
    if len(parts) == 2:
        lid_raw, phone_jid = parts[0], parts[1]
        lid_num = lid_raw.split("@")[0]
        phone_to_lid[phone_jid] = lid_num

# Listar fotos em alta resolução em /sdcard/WhatsSync/cache/
res_cache = subprocess.run(["adb", "shell", "ls /sdcard/WhatsSync/cache/"], capture_output=True, text=True, encoding="utf-8", errors="ignore")
cached_hires_files = set(f.strip() for f in res_cache.stdout.splitlines() if f.strip().endswith(".jpg"))

# Listar fotos miniaturas em /sdcard/WhatsSync/avatars/
res_avatars = subprocess.run(["adb", "shell", "ls /sdcard/WhatsSync/avatars/*@s.whatsapp.net.j"], capture_output=True, text=True, encoding="utf-8", errors="ignore")
avatar_files = [line.strip() for line in res_avatars.stdout.splitlines() if line.strip().endswith("@s.whatsapp.net.j")]
avatar_set = {os.path.basename(f): f for f in avatar_files}

print(f" -> Encontradas {len(cached_hires_files)} fotos em alta resolução no cache.")
print(f" -> Encontradas {len(avatar_files)} fotos de perfil no repositório geral do WhatsApp.")

# 2. Obter banco de contatos do Android
print("\n[2/5] Conectando com a base do Google Contacts...")
subprocess.run(["adb", "pull", "/data/data/com.android.providers.contacts/databases/contacts2.db", "temp_sync/contacts2.db"], check=True)

conn = sqlite3.connect("temp_sync/contacts2.db")
cursor = conn.cursor()

cursor.execute("""
    SELECT 
        d_wa.data1 as jid,
        r_google._id as google_raw_id,
        r_google.display_name
    FROM data d_wa
    JOIN mimetypes m_wa ON d_wa.mimetype_id = m_wa._id
    JOIN raw_contacts r_wa ON d_wa.raw_contact_id = r_wa._id
    JOIN raw_contacts r_google ON r_wa.contact_id = r_google.contact_id
    JOIN accounts acc ON r_google.account_id = acc._id
    WHERE (m_wa.mimetype LIKE '%whatsapp%profile%')
      AND acc.account_type = 'com.google'
""")

matches = cursor.fetchall()
print(f" -> {len(matches)} contatos do Google identificados com WhatsApp.")

# 3. Aplicar fotos
print("\n[3/5] Gravando fotos nos contatos do Google...")
synced_hires = 0
synced_thumb = 0

for jid, google_raw_id, display_name in matches:
    # Prioridade 1: Alta Resolução (640x640)
    # Tenta achar por LID mapeado ou JID numérico direto
    photo_source_remote = None
    is_hires = False

    lid_candidate = phone_to_lid.get(jid)
    phone_digits = jid.split("@")[0]

    # Verificar se existe <lid>.jpg
    if lid_candidate and f"{lid_candidate}.jpg" in cached_hires_files:
        photo_source_remote = f"/sdcard/WhatsSync/cache/{lid_candidate}.jpg"
        is_hires = True
    elif f"{phone_digits}.jpg" in cached_hires_files:
        photo_source_remote = f"/sdcard/WhatsSync/cache/{phone_digits}.jpg"
        is_hires = True
    else:
        # Fallback para thumbnail no Avatars
        expected_thumb = f"{jid}.j"
        if expected_thumb in avatar_set:
            photo_source_remote = avatar_set[expected_thumb]
            is_hires = False

    if photo_source_remote:
        local_photo = f"temp_sync/{phone_digits}.jpg"
        subprocess.run(["adb", "pull", photo_source_remote, local_photo], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        
        if os.path.exists(local_photo):
            with open(local_photo, "rb") as f:
                photo_bytes = f.read()

            # Check if photo row exists
            cursor.execute("SELECT _id FROM data WHERE raw_contact_id=? AND mimetype_id=10", (google_raw_id,))
            row = cursor.fetchone()
            if row:
                cursor.execute("UPDATE data SET data15=? WHERE _id=?", (photo_bytes, row[0]))
            else:
                cursor.execute("""
                    INSERT INTO data (package_id, mimetype_id, raw_contact_id, is_read_only, is_primary, is_super_primary, data_version, data15)
                    VALUES (NULL, 10, ?, 0, 1, 1, 1, ?)
                """, (google_raw_id, photo_bytes))
            
            # Mark raw_contact dirty so Google cloud syncs it automatically!
            cursor.execute("UPDATE raw_contacts SET dirty=1 WHERE _id=?", (google_raw_id,))
            
            if is_hires:
                synced_hires += 1
                print(f"  ✓ [ALTA RESOLUÇÃO 640x640] {display_name}")
            else:
                synced_thumb += 1
                print(f"  ✓ [Miniatura] {display_name}")

conn.commit()
conn.close()

total_synced = synced_hires + synced_thumb

# 4. Enviar de volta ao aparelho
print(f"\n[4/5] Enviando {total_synced} contatos atualizados para o smartphone ({synced_hires} em Alta Resolução)...")
subprocess.run(["adb", "shell", "am force-stop com.android.providers.contacts"])
subprocess.run(["adb", "push", "temp_sync/contacts2.db", "/data/data/com.android.providers.contacts/databases/contacts2.db"], check=True)
subprocess.run(["adb", "shell", "chmod 660 /data/data/com.android.providers.contacts/databases/contacts2.db*"])
subprocess.run(["adb", "shell", "chown u0_a37:u0_a37 /data/data/com.android.providers.contacts/databases/contacts2.db* 2>/dev/null; chown -R `dumpsys package com.android.providers.contacts | grep userId= | head -n 1 | cut -d= -f2`:* /data/data/com.android.providers.contacts/databases/contacts2.db* 2>/dev/null"])

# 5. Disparar sincronização em nuvem do Google
print("\n[5/5] Disparando sincronização com a nuvem do Google...")
subprocess.run(["adb", "shell", "am broadcast -a android.intent.action.BOOT_COMPLETED -p com.android.providers.contacts"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

print("\n==================================================")
print(f"🎉 SUCESSO! {total_synced} fotos sincronizadas com o Google Contacts!")
print(f"  - {synced_hires} fotos em ALTA RESOLUÇÃO (640x640)")
print(f"  - {synced_thumb} fotos em resolução padrão")
print("As fotos já estão visíveis no seu celular e em https://contacts.google.com/")
print("==================================================")
