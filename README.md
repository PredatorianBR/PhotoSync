# WhatsSync 📱✨

Aplicativo Android (Kotlin + Jetpack Compose) que automatiza a captura de fotos de perfil no **WhatsApp** e sincroniza diretamente com os contatos do aparelho e da sua conta **Google** ([contacts.google.com](https://contacts.google.com/)).

---

## 🚀 Como Funciona

1. **Leitura dos Contatos**: O app mapeia os contatos do seu smartphone e detecta quais estão associados ao WhatsApp ou WhatsApp Business via `ContactsContract`.
2. **Automação via Acessibilidade**: Através do `WhatsappAccessibilityService`, o aplicativo abre o perfil no WhatsApp, clica para expandir a foto de perfil em alta definição e captura a imagem (`takeScreenshot` de acessibilidade no Android 11+).
3. **Sincronização com o Google Contacts**: O app recorta e ajusta a foto para 512x512 JPEG e grava na conta Google do contato (`ContactsContract.RawContacts`), propagando automaticamente a atualização para a nuvem do Google.

---

## 🛠️ Como Compilar e Gerar o APK

### Opção A: Abrir no Android Studio (Recomendado)
1. Instale o [Android Studio](https://developer.android.com/studio) (ou execute `winget install Google.AndroidStudio` no terminal).
2. Abra esta pasta (`WhatsSync`) no Android Studio.
3. Aguarde o Gradle sincronizar as dependências automaticamente.
4. No menu superior, clique em **Build** > **Build Bundle(s) / APK(s)** > **Build APK(s)**.
5. O APK compilado será gerado em:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

### Opção B: Compilação Automática via GitHub Actions
O projeto já conta com o workflow [`.github/workflows/build.yml`](.github/workflows/build.yml).
Se você hospedar o projeto em um repositório GitHub, a cada push ou acionamento manual em *Actions*, o APK de depuração é compilado e disponibilizado para download imediatamente na aba **Artifacts**.

---

## 📲 Como Instalar e Testar no Celular

1. Transfira o arquivo `app-debug.apk` para o celular via USB, Google Drive, WhatsApp ou cabo.
2. No celular, clique para instalar (permita a instalação de fontes desconhecidas se solicitado).
3. Abra o **WhatsSync**:
   - Conceda a permissão de **Contatos** (Leitura e Escrita).
   - Clique no aviso para ativar o **Serviço de Acessibilidade do WhatsSync** em *Configurações > Acessibilidade > Apps Instalados*.
4. Selecione os contatos desejados e toque em **Iniciar Sincronização**.
5. O app automatizará a busca das fotos e atualizará seus contatos do Google!
