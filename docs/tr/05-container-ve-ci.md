# Faz 5 - Container ve CI

Tarih: 2026-09-15

English: [../en/05-container-and-ci.md](../en/05-container-and-ci.md)

Üç servisin imajları sıkılaştırıldı, her servis için bir GitHub Actions workflow'u yazıldı: test ve kapsam alt sınırı, SonarQube Cloud, imaj derleme ve Trivy taraması, tag atılınca GHCR'a gönderme ve GitHub Release. Hepsi yerelde denendi. GitHub'da çalışmaları için push, Sonar için de kurulum gerekiyor (aşağıda "YAPMAN GEREKEN").

## İmajlar

| İmaj | Taban | Kullanıcı | Boyut |
|---|---|---|---|
| collector | `eclipse-temurin:21-jre-alpine` | 10001 | 360 MB |
| api | `eclipse-temurin:21-jre-alpine` | 10001 | 379 MB |
| frontend | `nginxinc/nginx-unprivileged:1.29-alpine` | 101 | 83 MB |

- **Çok aşamalı**: Java'da Maven imajında derleniyor, çalışma imajında sadece JRE var. Frontend'de Node imajında build, nginx'te sadece `dist/`.
- **Katmanlı jar**: Spring Boot'un `jarmode=tools extract --layers` komutuyla jar dört katmana ayrılıyor: bağımlılıklar, loader, snapshot bağımlılıklar, uygulama kodu. Bağımlılıklar alttaki katmanda, kod değişince sadece en üstteki birkaç yüz KB'lık katman yeniden çekiliyor. Uygulama `JarLauncher` ile açılıyor (api 4,3 sn'de).
- **Build cache**: Maven deposu ve npm cache'i BuildKit cache mount'unda. pom ya da lock dosyası değişmedikçe bağımlılıklar yeniden inmiyor.
- **Root değil, sayısal UID**: Java imajlarında 10001. Kubernetes'in `runAsNonRoot` kontrolü kullanıcı adıyla çalışmıyor, sayı istiyor (Faz 7). nginx-unprivileged zaten 101 ile çalışıyor.
- **OCI etiketleri**: `org.opencontainers.image.source` GHCR'daki paketi repoya bağlıyor; sürüm `org.opencontainers.image.version` etiketinde.

### Ortam etiketi çalışma anında

Faz 4'te ortam etiketi (LOCAL/INT/PROD) build argümanıydı. Ama Faz 7'deki akışta aynı imaj önce INT'e, sonra PROD'a taşınacak; build sırasında gömülen etiket PROD'da "INT" gösterirdi. Etiketi çalışma anına aldım: nginx yapılandırması imajda şablon (`/etc/nginx/templates/default.conf.template`), nginx açılırken container'ın `APP_ENV` değişkeniyle dolduruluyor ve `/env.json` bunu veriyor. Frontend açılışta `/env.json`'u okuyor, gelmezse (`npm run dev`) varsayılan LOCAL. Sürüm numarası ise imajın parçası, build argümanı olarak kaldı. Compose'da `APP_ENV: LOCAL`, INT ve PROD'da Helm values'tan gelecek.

## Workflow'lar

`.github/workflows/collector.yml`, `api.yml`, `frontend.yml`. Üçü aynı yapıda:

- **Tetikleme**: `main`'e push ve PR, sadece o servisin dizini (frontend için ayrıca `CHANGELOG.md`) ya da workflow dosyası değişince (`paths`). `collector-v*`, `api-v*`, `frontend-v*` tag'leri. Elle çalıştırma (`workflow_dispatch`).
- **test job'u**:
  - Java: `mvn verify`. Testler (api'de Testcontainers ile PostgreSQL ve Redis, GitHub'ın runner'ında Docker var) ve JaCoCo'nun satır kapsamı alt sınırı: %85. 2026-09-15'te collector %91,0, api %91,8.
  - Frontend: `npm run test:coverage` (Vitest, alt sınır satır %50; ölçülen %53,5) ve `npm run build`.
  - Kapsam raporu artifact olarak yükleniyor.
  - SonarQube Cloud: Java'da Maven eklentisi, frontend'de `sonarqube-scan-action`. İkisinde de `sonar.qualitygate.wait=true`, quality gate kırmızıysa job kırılıyor.
- **image job'u** (test geçerse):
  - Buildx ile derleme, GitHub Actions cache'i (`type=gha`).
  - Trivy: `CRITICAL`, sadece düzeltmesi yayınlanmış açıklar (`ignore-unfixed`), bulursa job kırılıyor.
  - SBOM: Trivy ile CycloneDX, artifact olarak.
  - Tag'de: GHCR'a `ghcr.io/agern28/kesinti-haritasi/<servis>:X.Y.Z` ve `:latest`, ardından GitHub Release. Release notu `CHANGELOG.md`'deki `## [X.Y.Z]` bölümü, SBOM ekli.

### Kararlar

- **Action'lar SHA ile sabitlendi**: `actions/checkout@3d3c42e...  # v7.0.1` gibi. Tag'ler taşınabiliyor; bir action'ın tag'i ele geçirilirse sabit SHA etkilenmiyor. Güncellemek elle, yorumdaki sürümle birlikte.
- **İzinler en az**: workflow seviyesinde `contents: read`. Yazma izni (`packages`, `contents`) sadece image job'unda. Fork'tan gelen PR'larda token zaten salt okunur, GHCR ve Release adımları sadece tag'de çalışıyor.
- **Trivy `ignore-unfixed`**: düzeltmesi olmayan bir açık için yapılabilecek bir şey yok; onda kırmak pipeline'ı sebepsiz kilitler. Düzeltmesi olan açıkta kırıyor, nitekim ilk taramada kırdı (aşağıda).
- **GHCR'a sadece tag'de**: her `main` push'unda imaj derlenip taranıyor ama gönderilmiyor. Faz 7'de INT'e otomatik geçiş için bu değişebilir (CI, INT values'taki tag'i güncelleyecek).
- **Sonar token'ı yoksa atlanıyor**: `SONAR_TOKEN` tanımlı değilse Sonar adımı yerine bir uyarı basılıyor, job yeşil kalıyor. Token eklenene kadar workflow'lar kırmızı olmasın, fork PR'larında da çalışsın diye. Token eklendikten sonra quality gate zorunlu.

## Trivy ilk taramada kırdı

Yeni imajları yerelde Trivy'den geçirince collector ve api'de Tomcat 11.0.24'te üç CRITICAL açık çıktı (CVE-2026-65182, CVE-2026-65905, CVE-2026-68525), düzeltmesi 11.0.25'te. Spring Boot 4.1.1 en yeni sürüm, 11.0.24 getiriyor. İki pom'da `tomcat.version` 11.0.25'e sabitlendi; Spring Boot'un sonraki yamasında bu satır silinecek (pom'da not var). Sonra üç imaj da temiz.

## Yerelde denediklerim

- `mvn verify` iki serviste yeşil, JaCoCo alt sınırı geçti (collector 82 test, api 24 test).
- Frontend: 37 test, kapsam alt sınırı ve build yeşil.
- Üç imaj derlendi (ilk seferde 169 sn), compose'da beş container sağlıklı. Container'larda `id`: `uid=10001(app)`. `/env.json` `{"environment":"LOCAL"}` döndü.
- Trivy (CI ile aynı ayarlar): üç imajda da düzeltmesi olan CRITICAL açık yok.
- `actionlint` üç workflow'da temiz.
- Workflow'larda kullanılan her input'u, action'ın sabitlenen SHA'daki `action.yml`'iyle karşılaştırdım; eksik yok.

GitHub'da henüz çalışmadılar: push, SonarQube Cloud kurulumu ve ilk tag gerekiyor.

## Nerede takıldım

- **Trivy'nin bulduğu Tomcat açıkları** (yukarıda). Spring Boot'un yeni yamasını bekleyemezdim; sürümü tek başına yükseltmek bilinen yol.
- **actionlint'e yanlış bayrak**: `-color=never` diye bir seçenek yok, `-no-color`. Hata çıktısı input karşılaştırmasının sonucunu da gizledi.
- **Input karşılaştırma script'imin yanlış alarmı**: `actions/checkout`'ta `fetch-depth` yok dedi; `action.yml`'e elle bakınca var. Script'teki ayrıştırma bazı açıklama satırlarında şaşırıyor. Workflow doğru.
- **Ortam etiketi**: build argümanı olarak bırakırsam Faz 7'deki "aynı imaj INT'ten PROD'a" akışı bozulacaktı; bunu imajları tasarlarken fark ettim, Faz 7'ye kalmadı.

## Bilinen sınırlar

- Sonar token'ı eklenene kadar Sonar adımı atlanıyor, sadece uyarı var.
- İmajlar sadece amd64. Hetzner CX23 x86, şimdilik yetiyor.
- Tarayıcıyla yapılan arayüz denemesi (Faz 4, Playwright) CI'da değil.
- `latest` etiketi var ama GitOps tarafında (Faz 7) her zaman açık sürüm etiketi kullanılacak.
- Java imajları 360-380 MB. JRE'nin kendisi bunun çoğu; jlink ile küçültmek şimdilik gereksiz.

## YAPMAN GEREKEN

1. **SonarQube Cloud**
   1. https://sonarcloud.io adresine GitHub hesabınla gir.
   2. "Import an organization" ile `agern28` hesabını organizasyon olarak ekle (ücretsiz plan, public repo).
   3. "Analyze new project" ile `kesinti-haritasi` reposunu seç. Monorepo seçeneğiyle üç proje oluştur, anahtarlar tam olarak şu olmalı: `agern28_kesinti-haritasi_collector`, `agern28_kesinti-haritasi_api`, `agern28_kesinti-haritasi_frontend`.
   4. Her projede Administration > Analysis Method'da "Automatic Analysis"i kapat (analiz CI'dan geliyor, ikisi birlikte çalışmıyor).
   5. My Account > Security'den bir token oluştur.
2. **SONAR_TOKEN**: GitHub'da repo > Settings > Secrets and variables > Actions > New repository secret. Ad `SONAR_TOKEN`, değer az önceki token.
3. **GHCR paketlerini public yap**: ilk tag'ler atıldıktan sonra GitHub profilinde Packages sekmesinde `kesinti-haritasi/collector`, `api` ve `frontend` görünecek. Her birinde Package settings > Change visibility > Public.

## v1.0.0 tag'leri

Üç workflow da `main`'de yeşil olduktan ve Sonar kurulduktan sonra:

```bash
cd ~/projects/kesinti-haritasi
git checkout main && git pull
git tag -a collector-v1.0.0 -m "collector 1.0.0"
git tag -a api-v1.0.0 -m "api 1.0.0"
git tag -a frontend-v1.0.0 -m "frontend 1.0.0"
git push origin collector-v1.0.0 api-v1.0.0 frontend-v1.0.0
```

Her tag kendi workflow'unu başlatıyor: test, Trivy, GHCR'a gönderme, Release. Release notu `CHANGELOG.md`'deki `[1.0.0]` bölümünden geliyor.
