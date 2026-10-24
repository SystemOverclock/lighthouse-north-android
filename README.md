# client-android — SDK Android nativo da plataforma Lighthouse North (CODINOME)

> **Codinome:** o NOME DE PUBLICAÇÃO (Maven Central) é **PENDENTE de decisão
> do dono**. `client-android` é o nome interno neutro do monorepo (ADR-13);
> a publicação em Maven (coordenadas `com.lighthouse:*` provisórias) fica
> bloqueada até lá — mesmo critério do `client-flutter` (`publish_to: none`).

SDK Android **nativo e independente** (Kotlin) que integra o **app do cliente
final** à plataforma Lighthouse North de disparo push. A plataforma cuida do disparo e
da segmentação; o SDK coleta o **mínimo** e oferece opt-out fácil e
gratuito. Mesma API e semântica do SDK Flutter (`packages/client-flutter`) —
a spec vinculante é o `docs/NATIVE_CONTRACT.md` do monorepo.

## Compromissos (contrato de produto — herdado da 3.2)

1. **Minimização LGPD por construção**: o SDK coleta e envia EXATAMENTE
   `platform`, `token`, `contactRef` e `consent`. Sem IP custom, sem UA
   extra, sem identificadores de propaganda, sem dados do app hospedeiro.
2. **Plain-text por construção**: o SDK NÃO renderiza conteúdo de push — a
   exibição é do OS. Não há WebView/HTML no caminho.
3. **Opt-out fácil e gratuito** (requisito legal BLOQUEANTE): `optOut()` é
   best-effort com resultado explícito e nunca lança exceção de rede.
4. **Cobrança honesta**: permissão revogada nas Configurações ⇒ o SDK remove
   o device da plataforma sozinho (`onPermissionRevoked`).
5. **Estado local cifrado no Android Keystore** (fecha o P2 da 3.2): token e
   contactRef NUNCA em plaintext no SharedPreferences.
6. **SDK não embute Firebase** (token do app hospedeiro; sem Firebase ⇒
   erro tipado `FirebaseNotConfiguredException`, nunca crash).

## Instalação (até a publicação Maven)

```kotlin
// settings.gradle.kts do host
include(":client-android")
project(":client-android").projectDir = file("../packages/client-android/client")
// ou copie o módulo `client/` — ou aguarde as coordenadas Maven oficiais.
```

Requisitos: **minSdk 21**, compileSdk 35, Java 11+ (consumo). O SDK NÃO
adiciona NENHUMA dependência de runtime ao host (ver decisão abaixo).

## Integração passo a passo

### 1. App key + permissão (manifest do HOST)

A app key vem do painel do tenant (**Apps → Chaves**, material `ak_…`) e é
configuração — nunca hardcoded. Adicione ao manifest do SEU app:

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

(`INTERNET` já vem do manifest da library via merge; `POST_NOTIFICATIONS`
é runtime e responsabilidade do host — o SDK não injeta permissão de runtime
silenciosamente.)

### 2. Inicialize + opt-in

```kotlin
val client = Lighthouse NorthAndroidClient.init(
    context, appKey,                       // do painel — NUNCA hardcoded
    apiBaseUrl = "https://api.seu-ambiente.lighthouse",
)

// A partir de uma ação EXPLÍCITA do usuário (LGPD art. 7º):
client.registerForPush(
    activity,                              // opcional (usa a em resume)
    contactRef = "id-do-contato-no-seu-sistema", // external_id — opcional
    policyVersion = "2026-09",
) { result ->
    result.onSuccess {
        client.startHeartbeat()            // 24h com jitter ±10%
    }
}
```

O fluxo: (1) pede `POST_NOTIFICATIONS` (API 33+; abaixo já vem concedida);
(2) obtém o token FCM do SEU app; (3) `POST /v1/devices` com
`consent {granted: true, capturedAt, policyVersion}`; (4) persiste o mínimo
cifrado no Keystore. Permissão negada ⇒ callback com
`PushPermissionDeniedException` (não é crash — trate e siga).

### 3. Opt-out — OBRIGATÓRIO na UI do seu app

```kotlin
client.optOut { result ->
    result.onSuccess { r ->
        if (!r.fullyOptedOut) {
            // Falha de rede: em falha TOTAL o SDK preservou o estado —
            // re-tentar conclui. Nunca mostre "desativado" se não foi.
        }
    }
}
```

Ordem deliberada: (1) revoga a base de marketing (`granted=false` +
`contactRef`); (2) remove o device (`unregister`). Revogar DEPOIS recriaria
o device (o registro é upsert por token).

### 4. Rotação de token FCM (`onNewToken`) — automática

Declare o service OPCIONAL do SDK (só funciona em hosts COM Firebase — por
isso não vem no manifest da library):

```xml
<service
    android:name="com.lighthousenorth.clientandroid.Lighthouse NorthFirebaseMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

Ou, se você já tem seu próprio `FirebaseMessagingService`:

```kotlin
override fun onNewToken(token: String) {
    Lighthouse NorthAndroidClient.get().onNewToken(token) { /* resultado */ }
}
```

A rotação registra o token NOVO (upsert, SEM `consent` — rotação não é ato de consentimento (@gate QA 3.3: reafirmar `granted=true` ressuscitaria consentimento revogado no intervalo do optOut parcial; a base vigente no servidor é a autoridade)) e faz unregister
best-effort do antigo (evita push duplicado até o expurgo de 90d).

### 5. Revogação da permissão no OS (watcher)

O SDK observa o resume das activities: transição concedida→negada ⇒
unregister automático + `client.onPermissionRevoked = { event -> ... }`
(contactRef/policyVersion ficam para re-consentimento futuro). Limite
conhecido: a janela entre revogar nas Configurações e reabrir o app não é
observada (detecção no ON_RESUME — idem Flutter/iOS).

## Decisões de design

### OkHttp vs HttpURLConnection — **HttpURLConnection**

- **Zero dependências**: um SDK de terceiro que força OkHttp no host pode
  colidir com a versão que o host já pinou (OkHttp 4/5 e Kotlin versions).
  Higiene de dependência é requisito de produto aqui.
- As 3 rotas de devices são POSTs simples — sem pooling HTTP/2/interceptors.
- Timeout connect+read de 10s por tentativa, redirects desligados (a API
  nunca redireciona; remove a conversão silenciosa POST→GET do HUC).
- TLS/certificados: camadas do OS.
- Consequência: **sem ProGuard consumer rules** a emitir (nada para
  manter). Se a 3.4+ exigir OkHttp (ex.: HTTP/2 para FCM direto), vira ADR.

### Keystore (estado local)

- Chave AES-256-GCM **não exportável** no AndroidKeyStore (alias
  `lighthouse_north_client_state_v1`), IV novo por write (nunca reuso com a mesma
  chave — GCM), formato `base64(iv||ciphertext+tag)` em
  `SharedPreferences("lighthouse_north_client")["state_enc"]`.
- **Migração transparente** do plaintext legado `["state"]` (escrito pelos
  nativos da 3.2): lê, regrava cifrado, REMOVE o plaintext.
- Corrompido (tag inválida/JSON torto/versão desconhecida) ⇒ descarta e
  recomeça limpo (contrato §5). Keystore QUEBRADO ⇒ `Lighthouse NorthStorageException`
  — **fail closed**, nunca degrada para plaintext.
- Formato do JSON idêntico ao Flutter (v1) — nativo e embutido leem o estado
  um do outro se coexistirem no mesmo app.

### Permissão sem androidx (headless `android.app.Fragment`)

Para não exigir `androidx.fragment` (higiene de dependência), o dialog de
`POST_NOTIFICATIONS` usa um headless **platform fragment** (API 23+),
removido após o resultado. `android.app.Fragment` está deprecated desde a
28 mas é estável — o preço deliberado por zero dependências. Hosts que
preferem androidx podem implementar a port `PermissionGateway` com o
próprio mecanismo e injetá-la via `Config`.

### Firebase `compileOnly` + Threading

- `firebase-messaging`/`play-services-tasks` entram como `compileOnly`:
  hosts SEM Firebase recebem `FirebaseNotConfiguredException` (tipado).
- Todas as chamadas são serializadas em um executor single-thread do SDK
  (main thread nunca bloqueia); callbacks voltam nesse executor — poste
  para a main thread se a UI precisar. Variantes bloqueantes
  (`registerForPushBlocking`/`optOutBlocking`) para integradores com
  coroutines próprias.

## Segurança e rede (paridade do contrato)

TLS-only (`http://` só em `localhost`/`127.0.0.1`/`[::1]` para dev/testes) ·
timeout 10s/tentativa · retry 500ms→1s→2s com jitter ±25%, máx. 3, apenas
408/429/5xx · UA fixo `lighthouse-north-client-android/0.1.0 (kotlin;android)` ·
token/app key nunca logados inteiros (`redactSecret`: `fake...cdef`).

## Erros tipados (resumo)

`Lighthouse NorthException` (sealed): `PushPermissionDenied` · `FirebaseNotConfigured` ·
`PushTokenUnavailable` · `UnauthorizedAppKey` (401) · `Lighthouse NorthApi` (status +
errorCode) · `Lighthouse NorthNetwork` · `InsecureBaseUrl`/`InvalidBaseUrl` ·
`InvalidAppKey` · `Lighthouse NorthInvalidInput` · `Lighthouse NorthStorage` · `Lighthouse NorthNative`
(`no_activity`…) · ciclo de vida (`NotInitialized`/`ClientClosed`).

## Testes

```bash
cd packages/client-android
./gradlew :client:testDebugUnitTest   # 41 testes JVM — ZERO rede real
./gradlew :example-app:assembleDebug  # APK de integração
```

Suíte com engine gravador + scheduler manual (tempo virtual):
minimização provada por asserção de keys exatas, ordem do opt-out, falha
total preserva estado, jitter nos limites, TLS-only, retry exato. Os
caminhos Android (Keystore, dialog, watcher) são exercitados ao vivo pelo
`examples/android-app` no emulador (evidência da fatia 3.3).

## Exemplo (`examples/android-app`)

App mínimo com o fluxo legal completo (opt-in com consentimento → estado →
opt-out sempre visível). Dois modos: **transporte fake** (demo offline,
engine scriptado — zero rede) e **real** (default `http://10.0.2.2:3000` =
API local do compose vista do emulador). Token via `FakeTokenProvider`
até o Firebase do dono existir — documentado na UI.

## Pendências (registro honesto)

- **Publicação Maven** bloqueada pelo NOME (decisão do dono) — coordenadas
  e POM de publicação vêm com o nome.
- Rotação `onNewToken` real e dialog de permissão exigem **Firebase do
  host** (ação do dono p/ o projeto Lighthouse North; hosts finais usam o SEU
  Firebase — o caminho já funciona).
- Watch contínuo de permissão (entre revogação e reabertura) — herança
  conhecida da 3.2, candidato a Job/observer dedicado.

## Licença

MIT (ver [LICENSE](LICENSE)).
