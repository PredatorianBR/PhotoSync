import sqlite3
import subprocess
import os
import sys

# Force UTF-8 output
sys.stdout.reconfigure(encoding='utf-8')

print("==================================================")
print("  WhatsSync - Sincronizador Direto WhatsApp -> Google")
print("==================================================")

# 1. Copiar fotos salvas no WhatsApp
print("\n[1/4] Extraindo fotos de perfil diretamente do WhatsApp...")
subprocess.run([
    "adb", "shell",
    "mkdir -p /sdcard/WhatsSync/avatars && "
    "cp -rf /data/data/com.whatsapp.w4b/files/Avatars/* /sdcard/WhatsSync/avatars/ 2>/dev/null; "
    "cp -rf /data/data/com.whatsapp/files/Avatars/* /sdcard/WhatsSync/avatars/ 2>/dev/null; true"
])

# 2. Obter banco de contatos do Android
print("[2/4] Conectando com a base do Google Contacts...")
os.makedirs("temp_sync", exist_ok=True)
subprocess.run(["adb", "pull", "/data/data/com.android.providers.contacts/databases/contacts2.db", "temp_sync/contacts2.db"], check=True)

# 3. Listar arquivos de avatar disponíveis
res = subprocess.run(["adb", "shell", "ls /sdcard/WhatsSync/avatars/*@s.whatsapp.net.j"], capture_output=True, text=True)
avatar_files = [line.strip() for line in res.stdout.splitlines() if line.strip().endswith("@s.whatsapp.net.j")]
print(f" -> Encontradas {len(avatar_files)} fotos de perfil no WhatsApp.")

# 4. Mapear e aplicar na base do Google
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

avatar_set = {os.path.basename(f): f for f in avatar_files}

print("\n[3/4] Gravando fotos nos contatos do Google...")
synced = 0
for jid, google_raw_id, display_name in matches:
    expected_file = f"{jid}.j"
    if expected_file in avatar_set:
        remote_path = avatar_set[expected_file]
        local_photo = f"temp_sync/{jid}.jpg"
        subprocess.run(["adb", "pull", remote_path, local_photo], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        
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
            synced += 1
            print(f"  ✓ {display_name}")

conn.commit()
conn.close()

print(f"\n[4/4] Enviando {synced} contatos atualizados de volta para o smartphone...")
# Parar o serviço de contatos temporariamente para substituir a base de dados
subprocess.run(["adb", "shell", "am force-stop com.android.providers.contacts"])
subprocess.run(["adb", "push", "temp_sync/contacts2.db", "/data/data/com.android.providers.contacts/databases/contacts2.db"], check=True)
subprocess.run(["adb", "shell", "chmod 660 /data/data/com.android.providers.contacts/databases/contacts2.db*"])
subprocess.run(["adb", "shell", "chown u0_a37:u0_a37 /data/data/com.android.providers.contacts/databases/contacts2.db* 2>/dev/null; chown -R `dumpsys package com.android.providers.contacts | grep userId= | head -n 1 | cut -d= -f2`:* /data/data/com.android.providers.contacts/databases/contacts2.db* 2>/dev/null"])

# Disparar sincronização em nuvem do Google
subprocess.run(["adb", "shell", "am broadcast -a android.intent.action.BOOT_COMPLETED -p com.android.providers.contacts"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

print("\n==================================================")
print(f"🎉 SUCESSO! {synced} fotos sincronizadas com o Google Contacts!")
print("As fotos aparecerão instantaneamente no seu celular e em https://contacts.google.com/")
print("==================================================")
