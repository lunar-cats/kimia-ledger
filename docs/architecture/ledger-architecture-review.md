# بازبینی مستقل معماری — Kimia Financial Ledger

تاریخ: 2026-11-14 · وضعیت: **پیشنهاد برای تصمیم مالک**. هیچ ADR با این سند `Accepted` نمی‌شود.
بازنگری: 2026-11-15، پس از تصمیم‌های نهایی مالک. تصمیم‌ها در ADRهای 0014 تا 0026 ثبت شده‌اند (ادغام 2026-11-15؛ نقشهٔ شماره‌های قدیم در `docs/adr/README.md`) که همگی
`Proposed` هستند تا مالک متن آن‌ها را بپذیرد. جاهایی که این سند تصمیم یا وضعیت قبلی را توضیح می‌دهد،
صریحاً «تاریخی» یا «پیش‌تر» نوشته شده است.
موضوع بازبینی: شاخهٔ `docs/arch-decisions` در commit `7604c2b` به‌اضافهٔ تغییرات commit‌نشدهٔ working tree
(ADRهای پیش‌نویس old 0014 تا old 0032، همگی `Proposed`). منبع مقایسه: مخزن Sarv در commit `a49a842`، فقط خواندنی.

برچسب‌هایی که در کل سند به کار می‌روند:

| برچسب | معنی |
|---|---|
| **[توجیه فنی]** | از شواهد یا مستندات رسمی نتیجه می‌شود. تأیید مالک فقط تشریفاتی است |
| **[تأیید مالک]** | یک انتخاب یا مبادله (trade-off) است. مالک باید تصمیم بگیرد |
| **[تصمیم مالک]** | مالک در 2026-11-15 تصمیم گرفته است؛ در ADR مربوط ثبت شده و تا پذیرش متن `Proposed` است |
| **[بنچمارک]** | پیش از پذیرش، اندازه‌گیری روی سخت‌افزار و داده‌های هدف لازم است |
| **[مسدود: اطلاعات]** | اطلاعات لازم وجود ندارد. سند عدد یا فرض جعل نمی‌کند |
| **NOT DECIDED** | تصمیم گرفته نشده است. فقط هشت مورد §20.1 باز مانده‌اند |

---

## 1. Executive Summary

**وضع فعلی.** Kimia هنوز پیاده‌سازی ندارد و فقط مستندات و اسکلت پروژه دارد. ماژول‌های `ledger-core`،
`ledger-application`، `ledger-persistence-oracle`، `ledger-api-rest` و `ledger-api-grpc` جز
`package-info.java` چیزی ندارند. تنها تست‌ها `ArchitectureTest` و `HealthEndpointTest` هستند. ADRهای
پیش‌نویس old 0014 تا old 0032 هم کیفیت خوبی دارند و هم درونی سازگارند، ولی روی **پیش‌فرض‌هایی** بنا شده‌اند که baseline
جدید آن‌ها را عوض می‌کند:

1. پیش‌تر Ledger مالک Account بود (`CreateAccount`، `account.proto`، old 0025). اکنون **Account متعلق به
   یک Account Service بیرونی است**، ولی **Wallet و BalanceBucket متعلق به Ledger هستند** (تصمیم مالک).
   Wallet و Account دو مفهوم جدا هستند.
2. پیش‌تر Oracle تنها پایگاه داده بود (old 0028). اکنون PostgreSQL 18 است.
3. پیش‌تر v1 رویداد و outbox نداشت (old 0021). اکنون Kafka با Outbox و Inbox است.
4. پیش‌تر hold بومی نبود (old 0022). اکنون hold یک قابلیت بومی Ledger است و به Wallet/Bucket بسته است.
5. پیش‌تر مانده فقط به صورت insert-only روی posting بود (old 0020). اکنون یک projection تراکنشیِ
   قابل‌تغییر وجود دارد.

**نتیجهٔ کلی.** از 19 ADR پیشنهادی: 6 **ACCEPT**، 9 **REVISE**، 4 **REJECT**، بدون DEFER (§19). کار
قبلی که ارزش نگه‌داشتن دارد: شمارهٔ ترتیبی بی‌فاصله (اکنون برای هر «position» مانده)، معنای idempotency،
قرارداد خطا، port تراکنش، ماژول contracts و انجماد قرارداد.

**مهم‌ترین توصیه‌ها**

1. **[تصمیم مالک] مدل Wallet/Bucket/Account** (§4.2): یک مالک می‌تواند چند Wallet داشته باشد و هر Wallet چند
   Bucket، معمولاً یکی برای هر asset. هر Bucket دقیقاً یک asset و یک `external_account_id` دارد که هر دو
   تغییرناپذیرند. شناسهٔ حساب را Account Service می‌سازد و مالک آن است؛ Ledger مالک Bucket و پیوند Bucket به
   Account است و هرگز شناسهٔ حساب نمی‌سازد. برای ماندهٔ مشتری، هویت مانده `(tenant_id, external_account_id,
   asset_code)` است و هر کدام دقیقاً یک Bucket دارد (unique index روی Bucketهای مشتری). یک حساب مشتری
   می‌تواند چند asset را که Account Service اعلام کرده پشتیبانی کند، هر asset با Bucket و ماندهٔ جدا. تجمیع
   ماندهٔ مشتریان مختلف ممنوع است و تجمیع فقط برای حساب‌های سیستمی مجاز است.
2. **[توجیه فنی] یک مدل مانده، بدون تکرار**: مانده فقط در `balance_projections` نگه داشته می‌شود و هر ردیف
   آن یک *position* است: یا یک Bucket، یا برای حساب‌های سیستمی ترکیب «Account بیرونی + asset» بدون Bucket.
   هر posting دقیقاً به یک position می‌خورد. ماندهٔ Wallet و ماندهٔ Account **جمع positionها هستند** و
   جداگانه ذخیره نمی‌شوند.
3. **[توجیه فنی]** journal و postingها append-only هستند. این با سه لایه اجرا می‌شود: نبود grant برای
   UPDATE/DELETE/TRUNCATE، trigger، و audit. هر posting `position_seq` و `balance_after` دارد. تعادل هر
   asset در هر تراکنش با یک constraint trigger از نوع deferred **در خود DB** اجرا می‌شود. DDL روی
   PostgreSQL 18.7 اجرا و آزموده شد (§2.2).
4. **[توجیه فنی]** چرخهٔ عمر Wallet و Bucket (ایجاد، تعلیق، فعال‌سازی، بستن) **محلی Ledger** است. با
   قفل ردیف Wallet و positionها با postingها خطی‌سازی (linearize) می‌شود و **به fence بین‌سرویسی نیاز
   ندارد**. فقط وضعیت Account بیرونی (freeze/close) به قرارداد fence و seal با Account Service نیاز دارد
   (§9).
5. **[تصمیم مالک]** Oracle دیگر هدف نیست؛ PostgreSQL 18 پایه است و پیش‌نویس old 0028 رد شده است. jOOQ OSS برای v1
   انتخاب شده است؛ قید مجوز «latest versions» یک ریسک است که با بررسی در CI و سیاست ارتقا مدیریت می‌شود
   (§5، ADR 0014).
6. **[توجیه فنی]** hold بومی است: روی Bucket، با capture و release جزئی. وضعیت فعلی در `holds` و تاریخچهٔ
   تغییرناپذیر در `hold_events` نگه داشته می‌شود (§8).
7. **[تصمیم مالک]** Payment Timeline به Payments/Integration تعلق دارد و یک read model از نوع Operations
   آن را تجمیع می‌کند (§10).
8. **[تصمیم مالک]** پول `NUMERIC(38,0)` و در Java `BigInteger` است (§7.2). Ledger یک سرویس Java مستقل از
   monolith .NET در Sarv است (§17، ADR 0014). مهاجرت تدریجی است، با shadow، و برای هر جریان یک مرجع ثبت
   واحد (§18).

### 1.1 جدول مالکیت در معماری نهایی

| مفهوم | مالک |
|---|---|
| Wallet | **Ledger** |
| BalanceBucket | **Ledger** |
| پیوند Bucket به Account (شناسهٔ Account از Account Service) | **Ledger** |
| Hold / Reservation | **Ledger** |
| Journal Transaction | **Ledger** |
| Posting | **Ledger** |
| Account | **External Account Service** |
| Account Classification | **External Account Service** |
| Account Lifecycle | **External Account Service** |
| Payment Intent | **Payments Service** |
| Payment Provider Attempt | **Payments/Integration** |
| Payment Operational Timeline | **Payments/Operations** |

---

## 2. Evidence and Existing Architecture

### 2.1 آنچه خوانده شد (شواهد موجود)

| حوزه | فایل‌ها |
|---|---|
| قواعد پروژه | `CLAUDE.md` |
| ADR | `docs/adr/0001`…`0032` (شماره‌های پیش‌نویس old؛ پس از ادغام 0001…0026)، `docs/adr/README.md` |
| دامنه و امنیت | `docs/domain/invariants.md`، `docs/security/threat-model.md`، `docs/security/checklist.md` |
| قرارداد | `contracts/proto/ledger/v1/{account,entry,error,ledger_service,money}.proto` |
| بازبینی قبلی | `docs/review/2026-11-14-architecture-review.md` |
| build | `build.gradle.kts`، `settings.gradle.kts`، `gradle/libs.versions.toml`، `*/build.gradle.kts`، `gradle-wrapper.properties` |
| تست | `ledger-server/src/test/java/com/kimialedger/server/ArchitectureTest.java`، `HealthEndpointTest.java` |
| Sarv / Ledger | `Domain/Entities/{Transaction,LedgerEntry,BalanceBucket,Reservation}`، `Domain/Enums/*`، `Domain/ValueObjects/{Money,CurrencyFraction}`، `Application/Commands/{PostTransaction,ReverseTransaction,RunLedgerAudit}`، `Application/Common/{LedgerOptimisticRetry,LedgerIdempotentPersistence,SystemBalanceProjection}`، `Infrastructure/Persistence/Repositories/LedgerUnitOfWork.cs`، `Infrastructure/Persistence/Configurations/*`، `Migrations/20260602120000_InitialLedgerSchema.cs`، `Contracts/ILedgerModule.cs`، `Contracts/Events/LedgerIntegrationEvents.cs`. برای مدل Wallet: `Domain/Entities/{Wallet,LedgerAccount,SystemAccount,WalletProductDefinition}`، `Application/Common/BalanceBucketResolver.cs`، `Commands/{InitializeWallets,CreateBalanceBucket,CloseBalanceBucket,SuspendBalanceBucket}`، `Configurations/{Wallet,LedgerAccount,BalanceBucket}Configuration.cs`، و `Host/.../Controllers/Ledger/WalletsController.cs` |
| Sarv / سایر | `src/Host/SarvNeoBank.Api/Program.cs` (پیکربندی Wolverine)، `Directory.Packages.props`، `global.json`، `Modules/Payments/.../PaymentIntent.cs` و `PaymentStatus.cs`، `docs/audit-logging.md`، `docs/http-logging.md`، فهرست `src/Shared/*/BusinessLogging/*` |

### 2.2 آنچه این بازبینی اجرا کرد

| فرمان | نتیجه |
|---|---|
| `java -version` | JDK نصب‌شده روی ماشین 26.0.1 است. build از toolchain با Java 25 استفاده می‌کند |
| `./gradlew test --offline` | **شکست** در مرحلهٔ پیکربندی، چون plugin به نام `com.diffplug.spotless:8.11.0` در cache محلی Gradle نبود. این شکست ربطی به کد ندارد |
| `./gradlew test` (آنلاین، در پس‌زمینه) | **بی‌نتیجه**. dependencyها دانلود و همهٔ ماژول‌ها compile شدند (`compileJava` و `compileTestJava` بدون خطا). ولی task `:ledger-server:test` حدود 35 دقیقه بدون هیچ خروجی ماند و هیچ فایل نتیجهٔ XML در `ledger-server/build/test-results/test/` ساخته نشد. این بازبینی task را متوقف کرد. پس وضعیت `ArchitectureTest` و `HealthEndpointTest` **نامعلوم** است و علت توقف بررسی نشد |
| `buf --version` | `buf` روی این ماشین نصب نیست. `buf lint` و `buf breaking` اجرا **نشدند** |
| DDL §7.3 (بازنگری 2026-11-15) روی container موقت `postgres:18` (PostgreSQL **18.7**، image digest `sha256:b9f6b8ff…`)، با `ON_ERROR_STOP=1`. container پس از اجرا حذف شد | **بدون خطا**: **32 جدول**، **65 trigger** غیرداخلی، **86 policy**، **67 index**. smoke test **فقط کارکردی، بدون همروندی و بدون بار**، با 22 سناریو و 35 بررسی که همه مطابق انتظار بودند: S01) دو Wallet برای یک مالک پذیرفته شد. S02) یک حساب مشتری (`acc_123`) سه Bucket با IRR، USDT و USD گرفت. S03) Bucket دوم برای همان حساب و asset در Wallet دیگر با `ux_bucket_customer_balance` رد شد. S04) Bucket برای assetی که حساب اعلام نکرده با FK رد شد. S05) position مستقیم برای حساب مشتری با FK scope و CHECK رد شد. S06) Bucket با scope ناهمخوان با حساب رد شد. S07) تراکنش‌های متعادل سه‌پایه commit شدند. S08) تراکنش نامتعادل در COMMIT با trigger I-4 رد شد. S09) فرمول موجودی آزاد (ارائه‌شده + سقف اضافه‌برداشت − reserved) با query محاسبه شد؛ DB آن را enforce نمی‌کند و قاعدهٔ `ledger-core` است. S10) در Wallet معلق: برداشت `CLIENT` رد شد، واریز `SYSTEM` پذیرفته شد، واریز `CLIENT` فقط با `policy_ref` پذیرفته شد. S11) در Wallet منجمد: capture عادی hold رد شد؛ `EXCEPTIONAL` بدون `approval_ref` با CHECK رد و با آن پذیرفته شد؛ release hold انجام شد. S12) capture + release بیش از مبلغ hold با CHECK رد شد. S13) posting روی Wallet در `PROVISIONING` رد شد. S14) posting با `account_version` ناموجود در snapshotها با FK رد شد. S15) reversal دوم رد شد. S16) استفادهٔ دوم از یک FX quote رد شد و نرخ ارزش‌گذاری جدا ذخیره شد. S17) تغییر `external_account_id` یک Bucket و `account_scope` یک حساب با trigger رد شد. S18) UPDATE روی posting و DELETE روی `wallets` برای `ledger_app` رد شد. S19) کلید تکراری saga رد شد. S20) جمع هر asset صفر بود. S21) بدون `app.tenant_id` صفر ردیف برگشت و درج برای tenant دیگر با RLS رد شد. S22) superuser هم با trigger متوقف شد (UPDATE posting، TRUNCATE `hold_events`، DELETE `balance_buckets`). **اجرا نشد**: قفل همزمان، deadlock، کارایی، Flyway، jOOQ. اجرای 2026-11-14 روی نسخهٔ پیشین DDL (24 جدول، 43 trigger، 60 policy، شامل پذیرش N:1) تاریخی است. **اجرای دوم در همان روز، پس از تغییر outbox (ADR 0024 D7)**: **32 جدول**، **68 trigger** غیرداخلی، **86 policy**، **69 index**. 35 بررسی smoke قبلی همچنان بدون خطا گذشت. 15 بررسی جدید outbox همه مطابق انتظار بودند: relay نمی‌تواند DELETE کند یا payload را تغییر دهد؛ `published_at` پس از ثبت reset نمی‌شود؛ `ledger_app` نمی‌تواند outbox یا inbox را حذف کند؛ `ledger_cleanup` ردیف منتشرنشده را حذف نمی‌کند، فقط ردیف منتشرشدهٔ ۲۰ روزه و inbox ۲۰ روزه را حذف کرد و ردیف‌های ۱ روزه ماندند؛ `ledger_cleanup` نمی‌تواند UPDATE یا TRUNCATE کند یا journal را لمس کند؛ TRUNCATE توسط owner رد شد. **اجرا نشد** در این دور هم: همروندی، بار، Flyway، jOOQ. **اجرای سوم در همان روز، DDL نهایی (ADR 0024 D2، D7.12)**: **33 جدول**، **71 trigger** غیرداخلی، **86 policy**، **72 index**. 35 بررسی smoke اصلی همچنان بدون خطا؛ 50 بررسی بیشتر (15 بررسی retention قبلی و 35 بررسی جدید: ترتیب کلید، sequence aggregate، قرنطینه و قواعد metadata) مطابق انتظار. پس از آن چهار خط DDL برای محدودیت‌های metadata تغییر کرد؛ شمارش‌ها بدون تغییر ماندند. در آزمون، محدودیت تکرار regex در PostgreSQL (۲۵۵) پیدا شد؛ محدودیت ۳۰۰ نویسه یک بررسی جداگانهٔ `length()` است. TRUNCATE روی `outbox_events` اکنون توسط خود PostgreSQL رد می‌شود، چون جدول `outbox_quarantine` به آن ارجاع می‌دهد؛ trigger آن به‌عنوان لایهٔ دوم باقی است. **اجرا نشد**: همروندی، بار، Flyway، jOOQ. |
| پرس‌وجوی metadata در Maven Central و صفحات وب | نتیجه در §5.3 آمده است |

هیچ تستی از Sarv اجرا نشد. Sarv یک harness بار دارد (`tests/SarvNeoBank.Modules.Ledger.LoadTests`،
با سناریوهای `distributed`، `hot` و `fee`)، ولی **هیچ نتیجهٔ ثبت‌شده‌ای از آن در مخزن پیدا نشد**.
تنها شاهد عملکرد، یک توضیح در کد است: `LedgerOptimisticRetry.cs` می‌گوید سیاست قبلی، یعنی 3 تلاش بدون
backoff، در سناریوی `hot` «on the large majority of concurrent writes» به `ConcurrencyConflict` ختم
می‌شد. این گزاره فقط یک comment است و داده‌ای پشت آن نیست.

### 2.3 معماری موجود Kimia

| موضوع | وضعیت (شاهد) |
|---|---|
| ماژول‌ها | هر شش ماژول در `settings.gradle.kts` تعریف شده‌اند. محتوای آن‌ها فقط `package-info.java` است |
| قیود معماری | `ArchitectureTest` جهت وابستگی‌ها را به سمت داخل و بدون framework enforce می‌کند. قاعدهٔ `oracle..` در آن hard-code شده است |
| نسخه‌ها | `libs.versions.toml`: Spring Boot 4.1.2، JUnit 6.0.3، ArchUnit 1.5.1. Gradle 9.9.1 با SHA-256 pin شده است |
| قرارداد | `ledger_service.proto` شامل این‌هاست: `CreateAccount/GetAccount/PostEntry/GetEntry/GetBalance/ListPostings`. هنوز `sequence_no`، `correlation_id` به صورت field و کد HTTP 422 را دارد، یعنی اصلاحات ADR 0017 و 0019 هنوز در proto اعمال نشده‌اند |
| پایگاه داده | هیچ migration یا DDL وجود ندارد. هیچ SQL‌ای در مخزن نیست |
| نکتهٔ مهم | خود `CLAUDE.md` هنوز «update balances → insert outbox row» را توصیف می‌کند، یعنی با baseline جدید هم‌خوان است و با ADRهای پیش‌نویس old 0020 و old 0021 در تعارض است. بنابراین مستندات فعلی ناسازگارند و مالک باید یکی را برگزیند |

---

## 3. Sarv vs Kimia Comparison

### 3.1 ادعاهای مقایسهٔ قبلی دربارهٔ Sarv: بررسی مستقل

| ادعا | نتیجه | شاهد |
|---|---|---|
| enum `Currency` فقط مقدار IRR دارد | **تأیید شد** | `Domain/Enums/Currency.cs`: `IRR = 1`. در `CurrencyFraction.cs` مقدار IRR برابر 1 است و هر مقدار دیگر exception می‌دهد |
| هر تراکنش فقط یک ارز دارد و FX پشتیبانی نمی‌شود | **تأیید شد** | `Transaction.TotalAmount` از نوع `Money` با یک ارز است. `LedgerEntry` ارز ندارد و ارز از حساب می‌آید. هیچ مسیر FX وجود ندارد |
| idempotency روی `(ReferenceId, OperationType)` است و payload مقایسه نمی‌شود | **تأیید شد** | `TransactionConfiguration.cs` شامل `HasIndex(ReferenceId, OperationType).IsUnique()` است. در `PostTransactionHandler.cs:38-44` اگر رکوردی موجود باشد بی‌قیدوشرط موفقیت برمی‌گردد. بنابراین مبلغ متفاوت با همان reference پاسخ قبلی را می‌گیرد |
| ردیف Transaction ستون RowVersion ندارد و تغییر می‌کند | **تأیید شد** | `TransactionConfiguration.cs` هیچ `IsRowVersion` ندارد. `Transaction.cs` فیلدهای `Status`، `PostedAt` و `CompensatingTransactionId` را تغییر می‌دهد (`MarkPosted`، `MarkPendingSettlement`، `MarkFailed`، `RecordCompensation`) |
| LedgerEntries با cascade حذف می‌شوند و تغییرناپذیری در سطح DB ندارند | **تأیید شد** | `InitialLedgerSchema.cs:241`: `FK_LedgerEntries_Transactions_TransactionId … onDelete: ReferentialAction.Cascade`. برای `DENY`، `GRANT`، `REVOKE`، `CREATE TRIGGER` و `INSTEAD OF` در `src/Modules/Ledger`، `deploy` و `tools` جست‌وجو شد و نتیجه‌ای نداشت |
| مانده حساب سیستمی با `ExecuteUpdate` و `balance = balance + delta` به‌روز می‌شود و deltaها ادغام می‌شوند | **تأیید شد** | `LedgerUnitOfWork.cs:12-21` و `63-72` (deltaها در یک dictionary جمع می‌شوند). `SystemBalanceProjection.cs` |
| مفهوم tenant در Ledger وجود ندارد | **تأیید شد** | واژهٔ `tenant` در کد Ledger فقط در `NoOpMessageBus.cs` (تست) آمده است. `docs/audit-logging.md` صریحاً می‌گوید «the system is single-tenant» |
| reservation فقط با capture کامل مصرف می‌شود | **تأیید شد** و یک یافتهٔ تازه | `Reservation.Consume` و `PostTransactionHandler.cs:135` شرط `totalDebit == reservation.Amount` دارند. **یافتهٔ تازه** (`PostTransactionHandler.cs:138-141`): اگر bucket هر مقدار `LockedBalance` غیرصفر داشته باشد، هر برداشتی که reservation ندارد با `FundsLocked` رد می‌شود، **حتی وقتی موجودی آزاد کافی است** |
| کار audit فقط تشخیص می‌دهد | **تأیید شد** | `RunLedgerAuditHandler.cs`: «Detection only — never repairs». پیمایش keyset و پنجره‌ای است |
| (تازه) Ledger در Sarv مالک Wallet و BalanceBucket است | **تأیید شد** | `Domain/Entities/{Wallet,BalanceBucket,WalletProductDefinition}`، `InitializeWalletsCommand`. این **با تصمیم مالک برای Kimia هم‌خوان است**. تفاوت: در Sarv حساب posting (`LedgerAccount`، `SystemAccount`) هم داخلی است، ولی در Kimia Account بیرونی است. جزئیات در §4.2 |
| (تازه) Sarv هیچ broker بیرونی ندارد | **تأیید شد** | `Program.cs:409-412`: `PersistMessagesWithSqlServer` و `UseDurableLocalQueues`. در `Directory.Packages.props` هیچ بستهٔ Kafka یا RabbitMQ نیست |
| (تازه) دو بار reverse شدن یک تراکنش با قید DB منع نشده است | **تأیید شد**، با قید | `CompensatingTransactionId` فقط index غیرunique دارد. محافظت به صورت غیرمستقیم از RowVersion روی bucketها می‌آید، چون reversal فقط برای انتقال بین دو مشتری مجاز است (`ReverseTransactionHandler.cs:50-65`) |
| (تازه) Payments و Ledger در دو تراکنش جدا می‌نویسند | **تأیید شد** | `PaymentStatus.NeedsReconciliation` با این توضیح: «bank transfer succeeded … but the ledger posting did not complete». فراخوانی‌ها در `PaymentIntentSaga.cs:104,215,268` هستند |

### 3.2 مقایسهٔ موضوعی

| موضوع | Sarv (پیاده‌سازی‌شده) | Kimia (ADRهای پیشنهادی) | توصیهٔ این سند |
|---|---|---|---|
| پایگاه داده | SQL Server، EF Core 10 | Oracle (old 0028) | PostgreSQL 18، jOOQ و JDBC |
| همروندی | RowVersion خوش‌بینانه، 8 تلاش، full-jitter (8ms تا 200ms) | قفل‌های بدبینانه با ترتیب مرتب | بدبینانه برای حساب‌های مشتری و ترکیبی برای حساب‌های داغ (§11) |
| idempotency | `(ReferenceId, OperationType)`، بدون hash و بدون ثبت رد | کلید واحد برای هر tenant، hash payload، ثبت ردهای وابسته به وضعیت | ADR 0018 با اصلاحات PostgreSQL |
| مانده | `BalanceBucket` قابل‌تغییر. حساب سیستمی با `+= delta` | `balance_after` فقط insert | projection قابل‌تغییر به‌اضافهٔ `balance_after` روی posting |
| hold | فقط capture کامل. state قابل‌تغییر بدون تاریخچه | hold بومی ندارد (old 0022) | hold بومی، جزئی، با `hold_events` |
| تغییرناپذیری | فقط در سطح کد. cascade delete وجود دارد | grant و Oracle Immutable Tables | grant، trigger، audit، بدون cascade |
| رویداد | Wolverine با durable local queue (outbox روی SQL Server) | در v1 ندارد | Outbox و Inbox روی Kafka |
| چند ارز | فقط IRR | چند ارز، با FX به دست caller | چند asset، با snapshot از FX quote |
| مالکیت | Ledger مالک Wallet، Bucket، `LedgerAccount` و `SystemAccount` است | Ledger مالک Account است و Wallet ندارد | Ledger مالک Wallet و Bucket است. Account، از جمله حساب سیستمی، بیرونی است (§4) |
| tenant | ندارد | از روز اول | از روز اول. RLS به‌عنوان لایهٔ دوم |

ستون «Kimia (ADRهای پیشنهادی)» وضعیت ADRهای 2026-11-14 را نشان می‌دهد و تاریخی است.

**جمع‌بندی منصفانه.** Sarv کد کارکننده، تست یکپارچه با Testcontainers روی SQL Server، تست معماری،
harness بار و audit تشخیصی دارد. Kimia هیچ‌کدام از این‌ها را ندارد. از سوی دیگر مدل Sarv با الزامات
جدید **ناسازگار** است: حساب posting در آن داخلی است، مانده در دو محل نگه داشته می‌شود (Bucket و `LedgerAccount`)، فقط یک ارز دارد، تراکنش در آن قابل‌تغییر است، و تغییرناپذیری در
سطح DB ندارد. پس ارزش اصلی Sarv برای Kimia در **سناریوهای تست، درس‌های همروندی و تجربهٔ عملیاتی** است و
نه در schema آن.

---

## 4. Recommended Service Boundaries

### 4.1 مالکیت

| سرویس | مالک چیست | به Ledger چه می‌دهد / از Ledger چه می‌گیرد |
|---|---|---|
| **External Account Service** | هویت و شناسهٔ پایدار Account، طبقه‌بندی و normal side، lifecycle و وضعیت، مجوزهای debit و credit، asset‌های مجاز، سیاست و سقف اضافه‌برداشت، و سایر metadata حساب. **حساب‌های سیستمی** (fee، clearing، settlement، FX) هم همین‌جا هستند | رویداد `AccountStateChanged` با `account_version`. RPC همگام `BatchGetAccounts`. Ledger را برای fence و seal صدا می‌زند (§9) |
| **Ledger (Kimia)** | **Wallet** (ایجاد، هویت، lifecycle، وضعیت، و پیوند به مالک با `owner_ref` بیرونی)، **BalanceBucket**، مانده‌های هر position (posted، reserved، available)، **Hold** روی Bucket، تراکنش و posting، حسابداری FX و settlement، reconciliation، تاریخچهٔ مالی سطح Wallet | APIهای مدیریت Wallet و Bucket، APIهای مالی، رویدادهای Kafka، و `ApplyAccountState` |
| **Payments / Integration** | Payment Intent، ارتباط با provider، retry، callback، **Payment Timeline** | Ledger را برای Wallet balance، hold، capture و post صدا می‌زند و رویدادهای آن را مصرف می‌کند |
| **Operations read model** | نمای تجمیعی فقط‌خواندنی | رویدادهای Payments و Ledger را مصرف می‌کند |

قواعد مرز:

- Ledger **هیچ جدول مرجعی برای Account ندارد**. `account_state_cache` و `account_snapshots` فقط
  cache یا snapshot غیرمرجع هستند. Ledger API‌ای برای ساختن، تغییر یا بستن Account ندارد. فقط
  `ApplyAccountState` را دارد که Account Service صدا می‌زند.
- Ledger **مالک مرجع Wallet و Bucket است** و API مدیریت آن‌ها را عرضه می‌کند (§13.1). قواعد سطح Wallet
  را هم Ledger اجرا می‌کند: وضعیت، Bucket اصلی (primary)، و بستن فقط با موجودی صفر و بدون hold.

### 4.2 نگاشت Wallet ↔ BalanceBucket ↔ Account

**شواهد Sarv** (که مدل `Wallet → BalanceBucket → LedgerAccount` را دارد):

| پرسش | شاهد در Sarv |
|---|---|
| یک Wallet چند Bucket دارد؟ | **بله**. `Wallet._balanceBuckets` و `Wallet.AddBucket`. Bucket این فیلدها را دارد: `WalletProductId` (MAIN، voucher، reward)، `SponsorId`، `IsPrimary`، `Status` |
| Bucket اصلی | یکی برای هر (Wallet، ارز): ایندکس unique فیلترشدهٔ `IX_BalanceBuckets_WalletId_IsPrimary_TotalBalanceCurrency` با فیلتر `[IsPrimary] = 1` (`InitialLedgerSchema.cs:307-312`) |
| ارز Bucket | **یک ارز** که ثابت است. از `TotalBalance.Currency` گرفته می‌شود و در `LedgerAccount.Currency` هم هست. `BalanceBucketResolver` در صورت mismatch، `CurrencyMismatch` برمی‌گرداند |
| Bucket ↔ حساب posting | **1:1**. `LedgerAccount.ForCustomerWallet(bucketId)` با ایندکس unique فیلترشده روی `BalanceBucketId`. postingها (`LedgerEntries`) به `LedgerAccountId` اشاره می‌کنند، نه به Bucket |
| Wallet ↔ مشتری | **یک Wallet برای هر مشتری**. `WalletConfiguration.cs:15` با `HasIndex(CustomerId).IsUnique()` |
| حساب سیستمی | `SystemAccount` نقش منطقی است و ارز ندارد. به ازای هر ارز یک `LedgerAccount` دارد (`(SystemAccountId, Currency)` unique). postingها به آن `LedgerAccount` می‌خورند |
| ماندهٔ Bucket در برابر ماندهٔ حساب | ماندهٔ مشتری روی `BalanceBucket` (Total و Locked) است و `LedgerAccount.BalanceMinorUnits` برای مشتری همیشه 0 است. ماندهٔ سیستم روی `LedgerAccount` است. پس **دو محل مانده** وجود دارد، بسته به نوع حساب |
| lifecycle | `WalletStatus` مقدارهای Active، Suspended و Closed را دارد، ولی **هیچ کدی Wallet را Suspended یا Closed نمی‌کند** (جست‌وجوی `WalletStatus.Suspended` و `WalletStatus.Closed` نتیجه‌ای نداشت). Bucket متدهای `Suspend` و `Close` را دارد. Close به `Total = 0` و `Locked = 0` نیاز دارد. Bucket اصلی بسته نمی‌شود (`CloseBalanceBucketHandler.cs`). credit به Bucket معلق مجاز است و فقط بستن آن را رد می‌کند (`BalanceBucket.Credit`) |
| حذف | Wallet → Buckets با `OnDelete(Cascade)` (`WalletConfiguration.cs:25`) |
| API | API مشتری در `WalletsController` فقط خواندنی است: balance، balances، تراکنش‌های ارسالی و دریافتی. ایجاد Wallet از `InitializeWalletsCommand` انجام می‌شود |

در Sarv **هیچ Account بیرونی وجود ندارد**. `LedgerAccount` داخلی است و نقش «حساب posting» را دارد.

**نگاشت پیشنهادی در Kimia**

| رابطه | تصمیم | وضعیت |
|---|---|---|
| Wallet → Bucket | **1:N** (شاهد Sarv) | [توجیه فنی] |
| Bucket → asset | **N:1، تغییرناپذیر**. Wallet چندارزی یعنی چند Bucket | [توجیه فنی] |
| Bucket اصلی | حداکثر یک Bucket اصلی باز برای هر (Wallet، asset). Bucket اصلی تا وقتی Wallet باز است بسته نمی‌شود | [توجیه فنی] بر اساس شاهد Sarv |
| Bucket → Account بیرونی | **هر Bucket دقیقاً یک `external_account_id` دارد که تغییرناپذیر است**. شناسه را Account Service می‌سازد و مالک آن است؛ Ledger فقط پیوند را نگه می‌دارد. Account طرف double-entry است و Bucket زیرحساب آن | [تصمیم مالک] |
| Account → Bucket | برای حساب مشتری، هر `(external_account_id, asset_code)` حداکثر یک Bucket دارد، برای همیشه (unique index جزئی روی Bucketهای `CUSTOMER`). یک حساب مشتری می‌تواند چند asset اعلام کند و برای هر asset یک Bucket جدا دارد؛ اگر Account Service حساب چند-asset ندارد، برای هر asset یک شناسهٔ جدا می‌دهد. گزینه‌های (b) N:1 و (c) omnibus که پیش‌تر بررسی شدند برای ماندهٔ مشتری رد شدند (تاریخی) | [تصمیم مالک] |
| چند Wallet → یک Account | برای حساب مشتری و یک asset **ممنوع**. Wallet دوم همان مالک حساب جداگانه می‌گیرد. برای حساب سیستمی فقط جایی که قاعده صریحاً اجازه دهد | [تصمیم مالک] |
| Wallet → مالک | `owner_ref` یک شناسهٔ بیرونی opaque است، بدون PII. **چند Wallet برای هر مالک** مجاز است؛ سقف و محدودیت محصول سیاست کسب‌وکار قابل پیکربندی است و در schema نیست | [تصمیم مالک] |
| حساب‌های سیستمی | postingها مستقیماً به «Account بیرونی + asset» می‌خورند (position بدون Bucket) و تجمیع در آن‌ها مجاز است. scope حساب (`CUSTOMER` یا `SYSTEM`) از Account Service می‌آید | [توجیه فنی] |
| کاتالوگ محصول Bucket (MAIN، VOUCHER و غیره) | `product_code` در Ledger؛ محتوای کاتالوگ پیکربندی کسب‌وکار است، نه schema | [تصمیم مالک] |

**نگاشت double-entry.** posting روی Bucket در دفتر کل یعنی posting روی `external_account_id` همان
Bucket، با جزئیات زیرحساب Bucket. ماندهٔ Account برای یک asset برابر **جمع همهٔ positionهایی است که آن
Account را دارند**، چه positionهای Bucket و چه position مستقیم. این مقدار محاسبه می‌شود و ذخیره نمی‌شود.
پس یک مدل مانده وجود دارد و ماندهٔ تکراری وجود ندارد. این برخلاف Sarv است که مانده را برای مشتری روی
Bucket و برای سیستم روی LedgerAccount نگه می‌داشت.

---

## 5. Target Architecture

### 5.1 Baseline و ارزیابی هر انتخاب

| انتخاب | ارزیابی |
|---|---|
| Java 25 و Spring Boot 4.x | **[تصمیم مالک]** سرویس مستقل Java، جدا از monolith .NET در Sarv (§17، ADR 0014). سازگاری مستند است (§5.3). ADR 0001 قبلاً پذیرفته شده است |
| PostgreSQL 18 | **[تصمیم مالک]** Oracle دیگر هدف نیست. مزایای مشخص: partial unique index با معنای درست NULL، constraint trigger از نوع deferred برای enforce کردن I-4، RLS بدون وابستگی به edition |
| jOOQ و JDBC | **[تصمیم مالک]** jOOQ OSS برای v1. شرح و ریسک در §5.2 |
| Flyway | **[توجیه فنی]** Redgate PostgreSQL 18 را «Verified» اعلام کرده است. این که نسخهٔ 12.4.0، که BOM مدیریت می‌کند، 18 را پوشش دهد **unverified** است و در spike بررسی شود |
| Kafka 4.x | **[توجیه فنی]** به شرط وجود مصرف‌کننده. مصرف‌کنندگان در brief نام برده شده‌اند |
| Modular monolith درون Ledger | **[توجیه فنی]** ماژول‌های journal، balance، hold، fx، settlement و reconciliation به صورت package با قواعد ArchUnit، در یک deployable و یک DB |
| Light CQRS | **[توجیه فنی]** port نوشتن و port خواندن از هم جدا هستند، ولی یک DB مشترک دارند. read replica فقط وقتی معرفی شود که بنچمارک نیاز را نشان دهد |
| بدون Event Sourcing کامل | **[توجیه فنی]** journal تغییرناپذیر به‌اضافهٔ `hold_events`، `wallet_status_events`، `bucket_status_events`، `settlement_batch_events` و `account_snapshots` امکان بازسازی و audit را فراهم می‌کند. state قابل‌تغییر (positionها، `holds`، وضعیت Wallet و Bucket) از همین رکوردهای تغییرناپذیر قابل اشتقاق است (§16.3). یک Event Store عمومی برای هر aggregate ارزشی اضافه نمی‌کند ولی upcasting، snapshot و پیچیدگی عملیاتی را اضافه می‌کند |
| Hibernate/JPA در مسیر نوشتن | **رد می‌شود**، طبق brief و ADR 0002. دلیل: کنترل صریح روی `FOR UPDATE`، ترتیب قفل و نبود UPDATE ضمنی |

### 5.2 jOOQ در برابر JDBC خام (`JdbcClient`)

| معیار | jOOQ OSS | `JdbcClient` |
|---|---|---|
| مجوز | Apache-2.0. طبق jooq.org/download (خوانده‌شده در 2026-11-14) مجاز است | Apache-2.0 |
| پایگاه‌های داده | OSS: «No commercial databases»، «PostgreSQL (latest versions)» | همه |
| ایمنی نوع | SQL از روی schema مهاجرت‌یافته تولید می‌شود و ستون یا جدول اشتباه خطای compile می‌دهد | فقط در زمان اجرا آشکار می‌شود |
| شفافیت SQL | DSL ممکن است جزئیات قفل را پنهان کند. پس قفل‌ها و upsertها در کد صریح نوشته و بازبینی شوند | SQL به همان شکلی که نوشته شده دیده می‌شود |
| هزینهٔ build | codegen لازم است: Flyway روی Testcontainers اجرا می‌شود و سپس jOOQ codegen | ندارد |
| ریسک | OSS فقط «latest versions» از PostgreSQL را پشتیبانی می‌کند. پس ارتقای jOOQ و ارتقای PostgreSQL به هم گره می‌خورند. اگر PostgreSQL در تولید عقب بماند، پشتیبانی ممکن است نیاز به مجوز تجاری داشته باشد. پشتیبانی صریح از 18 در 3.21.9 **unverified** است | ندارد |

**[تصمیم مالک] (2026-11-15):** jOOQ برای v1. DSL نوع‌امن برای پرس‌وجوهای عادی؛ SQL صریح برای `FOR UPDATE`،
`UPDATE … RETURNING`، CTE و advisory lock مجاز است. همهٔ jOOQ و SQL فقط در `ledger-persistence-postgres` است.
کلاس‌ها از schema مهاجرت‌یافته تولید می‌شوند. نسخه‌ها دقیق pin و در CI بررسی می‌شوند. Hibernate/JPA در مسیر
مالی ممنوع است. jOOQ می‌تواند برای پرس‌وجوهای گزارش هم به کار رود؛ DB تحلیلی جدا فقط پس از اندازه‌گیری.
شرط‌ها:

1. `org.jooq..` فقط در ماژول persistence مجاز باشد (قاعدهٔ ArchUnit).
2. همهٔ دستورهای قفل‌گیری (`FOR UPDATE`، `FOR KEY SHARE`، advisory lock) در یک کلاس متمرکز باشند و
   integration test داشته باشند.
3. یک spike روی PostgreSQL 18، jOOQ 3.21.9 و pgJDBC 42.7.14 انجام شود.

ریسک «latest versions» با یک job در CI (codegen و تست یکپارچه روی جفت pin‌شدهٔ PostgreSQL و jOOQ) و یک سیاست ارتقای PostgreSQL مدیریت می‌شود (ADR 0014). `JdbcClient` به‌عنوان چارچوب دوم کنار گذاشته شد.

### 5.3 نسخه‌های بررسی‌شده (2026-11-14)

| مؤلفه | نسخه | منبع | وضعیت |
|---|---|---|---|
| Spring Boot | 4.1.2، که در catalog پین شده و آخرین نسخهٔ 4.1.x است. 4.2.0-RC2 هم منتشر شده | Maven Central `maven-metadata.xml` | verified |
| Spring Boot ↔ Java | «requires at least Java 17 … compatible with versions up to and including Java 27». Spring Framework 7.0.10 | docs.spring.io/spring-boot/system-requirements.html (صفحهٔ 4.1.2) | verified، پس Java 25 پوشش داده می‌شود |
| jOOQ | 3.21.9. BOM اسپرینگ 4.1.2 هم `jooq.version=3.21.9` را مدیریت می‌کند | Maven Central، `spring-boot-dependencies-4.1.2.pom`، jooq.org/download | verified. OSS به Java 21 یا بالاتر نیاز دارد. PostgreSQL 18 به‌طور مشخص: unverified |
| pgJDBC | 42.7.14 (2026-10-07). BOM: 42.7.14 | jdbc.postgresql.org، BOM | verified. فهرست صریح سازگاری با 18: unverified. مجوز: BSD-2-Clause بنا بر بازبینی قبلی، در این بازبینی دوباره بررسی نشد |
| PostgreSQL | 18. image رسمی `postgres:18` در 2026-11-14 به **18.7** resolve شد (اجرا شد، §2.2). جست‌وجوی وب فقط تا 18.6 را نشان داد (AWS RDS، 2026-08-26) | Docker Hub، جست‌وجوی وب | 18.7: verified، از طریق `SELECT version()` |
| Flyway | BOM: 12.4.0. Maven Central: 13.12.2 (2026-11-12) | Maven Central، BOM، مستندات Redgate | پشتیبانی 18 در مستندات جاری verified است. برای 12.4.0: unverified |
| kafka-clients | BOM: 4.2.2. آخرین Apache Kafka: 4.4.0 (2026-10-22) | kafka.apache.org/community/downloads، Maven Central | verified. clientهای 4.x به broker 2.1 یا بالاتر نیاز دارند (KIP-896) و به Java 11 یا بالاتر (منبع ثانویه) |
| Spring for Apache Kafka | BOM: 4.1.2، که با kafka-clients 4.2.2 جفت شده | BOM | سازگاری فقط از روی جفت‌شدن در BOM استنتاج شده و تست نشده است |
| gRPC و protobuf | BOM: grpc-java 1.84.1، protobuf-java 4.36.2 | BOM | فقط به‌عنوان اطلاع. مجوزها طبق ADR 0026 بررسی شوند |
| Testcontainers | BOM: 2.0.5 | BOM | — |

**نتیجه:** BOM اسپرینگ بوت 4.1.2 یک مجموعهٔ سازگار مدیریت‌شده دارد: jOOQ 3.21.9، pgJDBC 42.7.14،
kafka-clients 4.2.2، spring-kafka 4.1.2 و Flyway 12.4.0. **پیشنهاد:** از نسخه‌هایی که BOM مدیریت می‌کند
استفاده شود و override نشوند، مگر وقتی Flyway 12.4.0 در spike با PostgreSQL 18 کار نکند. سازگاری
end-to-end این مجموعه **تست نشده است**.

---

## 6. Module and Package Structure

```
kimia-ledger/
  contracts/                         proto: ledger.v1 (API) + ledger.events.v1 (Kafka)
  ledger-core/                       com.kimialedger.core            JDK only
    ...core.money                    Money, AssetCode, Amount (BigInteger — §7.2)
    ...core.journal                  JournalTransaction, Posting, Direction, BalanceRule (I-4)
    ...core.balance                  BalanceProjection, AvailableBalance, OverdraftPolicy
    ...core.hold                     Hold, HoldState, HoldEvent (state machine)
    ...core.fx                       FxQuote, RoundingPolicy, FxConversionCheck
    ...core.settlement               SettlementBatch (state machine)
    ...core.reconciliation           Discrepancy, MatchRule
    ...core.wallet                   Wallet, BalanceBucket, WalletStatus, BucketRules (authoritative lifecycle)
    ...core.position                 Position (bucket | direct account+asset), PositionTarget
    ...core.account                  ExternalAccountRef, AccountSnapshot, PostingCapability   (no account lifecycle!)
  ledger-application/                com.kimialedger.application     use cases + ports
    ...application.journal           PostTransaction, ReverseTransaction, GetTransaction
    ...application.hold              CreateHold, CaptureHold, ReleaseHold, ExpireHolds
    ...application.fx / settlement / reconciliation
    ...application.wallet            CreateWallet + provisioning saga, Suspend/Freeze/Activate/StartClosing/CloseWallet, AddBucket, balances
    ...application.account           ApplyAccountState (fence/seal), AccountResolver (cache→remote)
    ...application.port.out          LedgerTransactions (ADR 0026), AccountDirectory, Outbox, Clock, IdGenerator
  ledger-contracts/                  com.kimialedger.contracts       generated stubs only; mapping is in the adapters (ADR 0026 D2)
  ledger-persistence-postgres/       com.kimialedger.persistence.postgres   jOOQ/JDBC, Flyway, ALL SQL
  ledger-messaging-kafka/            com.kimialedger.messaging.kafka  outbox relay, inbox consumers
  ledger-account-client/             com.kimialedger.accountclient    gRPC client to Account Service (outbound adapter)
  ledger-api-rest/  ledger-api-grpc/ thin inbound adapters
  ledger-server/                     Spring Boot wiring only; ArchUnit
```

قواعد ArchUnit که باید اضافه شوند:

- `org.jooq..` و `org.postgresql..` فقط در `persistence.postgres..` مجاز باشند. این قاعده جای `oracle..`
  را در `ArchitectureTest` می‌گیرد.
- `org.apache.kafka..` فقط در `messaging.kafka..` مجاز باشد.
- `core.reconciliation` و `core.settlement` بتوانند به `core.journal` وابسته باشند، ولی عکس آن مجاز
  نباشد.
- `core.account` هیچ متد lifecycle نداشته باشد. این قاعده با بازبینی کد کنترل می‌شود، چون ArchUnit آن را
  به‌سادگی بیان نمی‌کند.

---

## 7. Database Schema and DDL

### 7.1 اصول

- **[توجیه فنی]** همهٔ کلیدها با `tenant_id` شروع می‌شوند و FKها composite هستند (ADR 0020). هیچ
  `ON DELETE CASCADE` وجود ندارد. همه `ON DELETE RESTRICT` هستند. ردیف‌های مالی، حتی جدول‌های state
  مانند `wallets`، `balance_buckets` و `balance_projections`، با trigger از DELETE و TRUNCATE محافظت
  می‌شوند.
- **[توجیه فنی] جدول مرجع Account وجود ندارد.** `external_account_id` یک شناسهٔ بیرونی است.
  `account_state_cache` (برای وضعیت جاری و قفل anchor) و `account_snapshots` (برای audit) غیرمرجع
  هستند. هیچ FK از posting، Bucket یا position به آن‌ها وجود ندارد. یکپارچگی این ارجاع را app و
  reconciliation بررسی می‌کنند.
- **[توجیه فنی] Wallet و Bucket جدول‌های مرجع Ledger هستند.** `wallets` و `balance_buckets` وضعیت جاری
  را نگه می‌دارند و تاریخچهٔ تغییرناپذیر در `wallet_status_events` و `bucket_status_events` است.
  ستون‌های هویتی Bucket (Wallet، asset، Account، primary، محصول) با trigger تغییرناپذیرند.
- **[توجیه فنی] Position.** `balance_projections` تنها جدول مانده است. هر ردیف یک position است: یا
  `balance_bucket_id IS NOT NULL` (Bucket)، یا position مستقیم برای «Account + asset» (حساب سیستمی). دو
  partial unique index هر کدام را یکتا می‌کنند. یک FK چهارستونی تضمین می‌کند Account و asset هر position
  Bucket با Bucket خودش یکی باشد.
- **[تصمیم مالک] Bucket مشتری یکتا.** `ux_bucket_customer_balance (tenant_id, external_account_id, asset_code) WHERE
  account_scope = 'CUSTOMER'` روی همهٔ وضعیت‌ها. Bucket به `account_asset_state` (assetهای اعلام‌شدهٔ حساب) و
  به `account_state_cache` (scope) FK دارد؛ position مستقیم فقط برای `SYSTEM` مجاز است (CHECK).
- **[تصمیم مالک] نسخهٔ حساب.** هر posting به `account_snapshots (tenant, external_account_id, account_version)` FK دارد.
  سقف اضافه‌برداشت برای هر asset در `account_asset_state` و `account_asset_snapshots` است.
- **[توجیه فنی] Posting** به `position_id` اشاره می‌کند. برای خوانایی و audit، ستون‌های
  `external_account_id`، `asset_code`، `balance_bucket_id` و `wallet_id` هم در خود posting تکرار
  می‌شوند و این تکرار با FK به position و Bucket بررسی می‌شود. تعادل I-4 در هر asset روی **همهٔ**
  postingهای تراکنش محاسبه می‌شود و به Bucket یا مستقیم بودن position بستگی ندارد.
- شناسه‌ها `uuid` هستند و در برنامه ساخته می‌شوند (UUIDv7). `outbox_id` یک identity است.

### 7.2 پول: `NUMERIC(38,0)` (تصمیم‌شده)

| معیار | `bigint` و `long` | `NUMERIC(38,0)` و `BigInteger` |
|---|---|---|
| بازه | ±9.22×10^18 واحد خرد | تا 10^38−1 |
| IRR با scale 0 (Sarv: `CurrencyFraction` IRR=1) | کافی است | کافی است |
| asset با scale 18، مثلاً `wei` | **ناکافی**: 2^63 wei حدود 9.22 واحد است | کافی است |
| کارایی | سریع‌تر است. **[بنچمارک]** | محاسبهٔ نرم‌افزاری. هزینه اندازه‌گیری نشده است |
| تغییر بعدی | تغییر نوع ستون روی جدول append-only و بزرگ به معنای بازنویسی جدول است | — |

**[تصمیم مالک] (2026-11-15):** فهرست asset‌ها fiat، crypto، token و stock را با scale تا 18 پوشش می‌دهد، پس
ذخیره‌سازی `NUMERIC(38,0)` و در Java یک `Amount` تغییرناپذیر با `BigInteger` است. سقف هر asset در
`assets.max_amount` ثبت و enforce می‌شود. روی wire مبلغ به صورت **decimal string** فرستاده می‌شود. float و
double هرگز استفاده نمی‌شوند. هزینهٔ `NUMERIC` نسبت به `bigint` همچنان [بنچمارک] است (ADR 0015). نوع
asset یکی از `FIAT`، `CRYPTO`، `TOKEN`، `STOCK` و `OTHER` است.

### 7.3 DDL کامل (PostgreSQL 18)

```sql
-- ============ 0. Roles (run once by DBA; passwords/certs from Vault, never in this file) ============
CREATE ROLE ledger_owner     NOLOGIN;                 -- owns all objects
CREATE ROLE ledger_migrator  LOGIN IN ROLE ledger_owner;   -- Flyway only; initSql: SET ROLE ledger_owner
CREATE ROLE ledger_app       LOGIN;                   -- runtime (API + workers)
CREATE ROLE ledger_relay     LOGIN;                   -- outbox relay: publish path only (no DELETE)
CREATE ROLE ledger_cleanup   LOGIN;                   -- retention cleanup of published outbox rows and old inbox rows
CREATE ROLE ledger_readonly  LOGIN;                   -- reconciliation, audit jobs, reporting
CREATE ROLE ledger_operator  LOGIN;                   -- tenant registry and approved configuration
CREATE ROLE ledger_repair    NOLOGIN;                 -- break-glass projection repair and outbox quarantine; granted per incident, audited

CREATE SCHEMA ledger AUTHORIZATION ledger_owner;
REVOKE ALL ON SCHEMA ledger FROM PUBLIC;
SET ROLE ledger_owner;                                -- as Flyway does, so objects are owned by ledger_owner
SET search_path = ledger;

-- ============ 1. Reference data and configuration ============
CREATE TABLE tenants (
  tenant_id     uuid        PRIMARY KEY,
  issuer        text        NOT NULL,
  tenant_claim  text        NOT NULL,
  status        text        NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED')),
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (issuer, tenant_claim)                                  -- ADR 0020
);

CREATE TABLE assets (                                             -- append-only; new scale = new code (ADR 0015)
  asset_code   varchar(16)   PRIMARY KEY CHECK (asset_code ~ '^[A-Z0-9][A-Z0-9_.-]{1,15}$'),
  asset_kind   text          NOT NULL CHECK (asset_kind IN ('FIAT','CRYPTO','TOKEN','STOCK','OTHER')),
  scale        smallint      NOT NULL CHECK (scale BETWEEN 0 AND 18),
  max_amount   numeric(38,0) NOT NULL CHECK (max_amount > 0),
  created_at   timestamptz   NOT NULL DEFAULT now()
);

CREATE TABLE tenant_settings (                                    -- operator-maintained; one row per tenant
  tenant_id              uuid          PRIMARY KEY REFERENCES tenants ON DELETE RESTRICT,
  reporting_asset_code   varchar(16)   REFERENCES assets ON DELETE RESTRICT,   -- NULL until the business decides (ADR 0025)
  valuation_policy_ref   varchar(128),                                         -- NULL until a valuation policy is approved
  account_cache_max_age  interval      NOT NULL DEFAULT interval '30 seconds'
                                       CHECK (account_cache_max_age > interval '0'),  -- initial value, not an SLO (ADR 0022)
  approval_ref           varchar(128)  NOT NULL,
  updated_at             timestamptz   NOT NULL DEFAULT now()
);

CREATE TABLE hold_policies (                                      -- versioned, append-only; no universal TTL (ADR 0023)
  tenant_id               uuid          NOT NULL REFERENCES tenants ON DELETE RESTRICT,
  policy_code             varchar(32)   NOT NULL,
  policy_version          integer       NOT NULL CHECK (policy_version >= 1),
  default_ttl             interval      NOT NULL CHECK (default_ttl > interval '0'),
  max_ttl                 interval      NOT NULL,
  capture_when_suspended  boolean       NOT NULL DEFAULT false,
  approval_ref            varchar(128)  NOT NULL,
  created_at              timestamptz   NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, policy_code, policy_version),
  CHECK (max_ttl >= default_ttl)
);

-- ============ 2. External ACCOUNTS: non-authoritative cache + immutable snapshots (ADR 0022) ============
CREATE TABLE account_state_cache (                                -- current view; lock anchor ① for fencing
  tenant_id            uuid          NOT NULL REFERENCES tenants ON DELETE RESTRICT,
  external_account_id  varchar(128)  NOT NULL CHECK (external_account_id ~ '^[\x21-\x7E]+$'),
  account_version      bigint        NOT NULL CHECK (account_version >= 1),
  account_scope        text          NOT NULL CHECK (account_scope IN ('CUSTOMER','SYSTEM')),
  account_class        text          NOT NULL,                     -- Account Service vocabulary, no PII
  source_status        text          NOT NULL,                     -- Account Service vocabulary, audit only
  can_debit            boolean       NOT NULL,
  can_credit           boolean       NOT NULL,
  normal_side          char(1)       NOT NULL CHECK (normal_side IN ('D','C')),
  closed               boolean       NOT NULL DEFAULT false,
  validated_at         timestamptz   NOT NULL,                     -- freshness is judged against tenant_settings.account_cache_max_age
  PRIMARY KEY (tenant_id, external_account_id),
  UNIQUE (tenant_id, external_account_id, account_scope),          -- FK target: buckets and positions agree on scope
  CHECK (NOT closed OR (NOT can_debit AND NOT can_credit))
);

CREATE TABLE account_asset_state (                                -- one row per asset the Account Service declares
  tenant_id            uuid          NOT NULL,
  external_account_id  varchar(128)  NOT NULL,
  asset_code           varchar(16)   NOT NULL REFERENCES assets ON DELETE RESTRICT,
  account_version      bigint        NOT NULL CHECK (account_version >= 1),
  supported            boolean       NOT NULL,                     -- withdrawal = false; rows are never deleted
  overdraft_limit      numeric(38,0) DEFAULT 0 CHECK (overdraft_limit >= 0),  -- 0 = none (default); NULL = no limit, SYSTEM only (app rule, ADR 0022)
  PRIMARY KEY (tenant_id, external_account_id, asset_code),
  FOREIGN KEY (tenant_id, external_account_id) REFERENCES account_state_cache ON DELETE RESTRICT
);

CREATE TABLE account_snapshots (                                  -- immutable; every posting references the version it used
  tenant_id            uuid          NOT NULL REFERENCES tenants ON DELETE RESTRICT,
  external_account_id  varchar(128)  NOT NULL,
  account_version      bigint        NOT NULL CHECK (account_version >= 1),
  account_scope        text          NOT NULL CHECK (account_scope IN ('CUSTOMER','SYSTEM')),
  account_class        text          NOT NULL,
  source_status        text          NOT NULL,
  can_debit            boolean       NOT NULL,
  can_credit           boolean       NOT NULL,
  normal_side          char(1)       NOT NULL CHECK (normal_side IN ('D','C')),
  closed               boolean       NOT NULL,
  source               text          NOT NULL CHECK (source IN ('EVENT','LOOKUP','FENCE')),
  source_event_id      uuid,
  source_occurred_at   timestamptz,
  recorded_at          timestamptz   NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, external_account_id, account_version)
);

CREATE TABLE account_asset_snapshots (                            -- immutable per-asset part of a snapshot
  tenant_id            uuid          NOT NULL,
  external_account_id  varchar(128)  NOT NULL,
  account_version      bigint        NOT NULL,
  asset_code           varchar(16)   NOT NULL REFERENCES assets ON DELETE RESTRICT,
  supported            boolean       NOT NULL,
  overdraft_limit      numeric(38,0) CHECK (overdraft_limit >= 0),
  PRIMARY KEY (tenant_id, external_account_id, account_version, asset_code),
  FOREIGN KEY (tenant_id, external_account_id, account_version)
    REFERENCES account_snapshots (tenant_id, external_account_id, account_version) ON DELETE RESTRICT
);

-- ============ 3. WALLETS and BALANCE BUCKETS (authoritative in Ledger) ============
CREATE TABLE wallets (
  tenant_id    uuid         NOT NULL REFERENCES tenants ON DELETE RESTRICT,
  wallet_id    uuid         NOT NULL,
  owner_ref    varchar(128) NOT NULL CHECK (owner_ref ~ '^[\x21-\x7E]+$'),  -- opaque external identity ref, no PII
  wallet_type  varchar(32)  NOT NULL DEFAULT 'DEFAULT',
  status       text         NOT NULL CHECK (status IN ('PROVISIONING','ACTIVE','SUSPENDED','FROZEN','CLOSING','CLOSED')),
  version      bigint       NOT NULL DEFAULT 0,
  created_at   timestamptz  NOT NULL,
  updated_at   timestamptz  NOT NULL,
  closed_at    timestamptz,
  PRIMARY KEY (tenant_id, wallet_id),
  CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);
-- Several wallets per owner (ADR 0021): no unique index on the owner; limits are business policy, not schema.
CREATE INDEX ix_wallet_owner ON wallets (tenant_id, owner_ref, wallet_type);

CREATE TABLE wallet_status_events (                               -- immutable lifecycle history
  tenant_id        uuid         NOT NULL,
  wallet_id        uuid         NOT NULL,
  event_seq        integer      NOT NULL CHECK (event_seq >= 1),
  from_status      text,
  to_status        text         NOT NULL CHECK (to_status IN ('PROVISIONING','ACTIVE','SUSPENDED','FROZEN','CLOSING','CLOSED')),
  reason_code      text         NOT NULL,
  approval_ref     varchar(128),                                  -- required by the service for FROZEN and its release (ADR 0021)
  idempotency_key  varchar(255) NOT NULL,
  client_id        varchar(255) NOT NULL,
  correlation_id   varchar(128) NOT NULL,
  occurred_at      timestamptz  NOT NULL,
  PRIMARY KEY (tenant_id, wallet_id, event_seq),
  FOREIGN KEY (tenant_id, wallet_id) REFERENCES wallets (tenant_id, wallet_id) ON DELETE RESTRICT
);

CREATE TABLE wallet_provisioning (                                -- saga state (ADR 0021); idempotent and resumable
  tenant_id            uuid          NOT NULL,
  wallet_id            uuid          NOT NULL,
  state                text          NOT NULL CHECK (state IN ('REQUESTED','ACCOUNTS_REQUESTED','MAPPED','ACTIVATED','FAILED')),
  requested_assets     varchar(16)[] NOT NULL CHECK (cardinality(requested_assets) >= 1),
  account_request_key  varchar(255)  NOT NULL,                     -- idempotency key sent to the Account Service, stable across retries
  attempt_count        integer       NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  next_attempt_at      timestamptz,
  last_error_code      text,
  created_at           timestamptz   NOT NULL,
  updated_at           timestamptz   NOT NULL,
  version              bigint        NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id, wallet_id),
  UNIQUE (tenant_id, account_request_key),
  FOREIGN KEY (tenant_id, wallet_id) REFERENCES wallets (tenant_id, wallet_id) ON DELETE RESTRICT,
  CHECK (state <> 'FAILED' OR last_error_code IS NOT NULL)
);
CREATE INDEX ix_wprov_due ON wallet_provisioning (next_attempt_at)
  WHERE state IN ('REQUESTED','ACCOUNTS_REQUESTED','MAPPED');     -- provisioning worker

CREATE TABLE wallet_provisioning_events (                         -- immutable saga history
  tenant_id    uuid        NOT NULL,
  wallet_id    uuid        NOT NULL,
  event_seq    integer     NOT NULL CHECK (event_seq >= 1),
  from_state   text,
  to_state     text        NOT NULL,
  detail_code  text,
  occurred_at  timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, wallet_id, event_seq),
  FOREIGN KEY (tenant_id, wallet_id) REFERENCES wallet_provisioning (tenant_id, wallet_id) ON DELETE RESTRICT
);

CREATE TABLE balance_buckets (
  tenant_id            uuid          NOT NULL,
  bucket_id            uuid          NOT NULL,
  wallet_id            uuid          NOT NULL,
  asset_code           varchar(16)   NOT NULL REFERENCES assets ON DELETE RESTRICT,
  external_account_id  varchar(128)  NOT NULL,                     -- supplied by the Account Service, never invented by Ledger
  account_scope        text          NOT NULL CHECK (account_scope IN ('CUSTOMER','SYSTEM')),
  product_code         varchar(32)   NOT NULL,                     -- validated against configurable business policy
  is_primary           boolean       NOT NULL,
  sponsor_ref          varchar(128),                               -- Sarv SponsorId analogue; opaque
  status               text          NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED','FROZEN','CLOSED')),
  version              bigint        NOT NULL DEFAULT 0,
  created_at           timestamptz   NOT NULL,
  updated_at           timestamptz   NOT NULL,
  closed_at            timestamptz,
  PRIMARY KEY (tenant_id, bucket_id),
  UNIQUE (tenant_id, bucket_id, external_account_id, asset_code, account_scope),  -- target for the position FK
  UNIQUE (tenant_id, bucket_id, wallet_id),                                       -- target for posting/hold FKs
  FOREIGN KEY (tenant_id, wallet_id) REFERENCES wallets (tenant_id, wallet_id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, external_account_id, asset_code)                         -- the account declares this asset
    REFERENCES account_asset_state (tenant_id, external_account_id, asset_code) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, external_account_id, account_scope)                      -- scope agrees with the account
    REFERENCES account_state_cache (tenant_id, external_account_id, account_scope) ON DELETE RESTRICT,
  CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);
CREATE UNIQUE INDEX ux_bucket_primary ON balance_buckets (tenant_id, wallet_id, asset_code)
  WHERE is_primary AND status <> 'CLOSED';                          -- one open primary per wallet+asset
CREATE UNIQUE INDEX ux_bucket_customer_balance ON balance_buckets (tenant_id, external_account_id, asset_code)
  WHERE account_scope = 'CUSTOMER';                                 -- one customer bucket per account+asset, for ever (ADR 0021)
CREATE INDEX ix_bucket_wallet  ON balance_buckets (tenant_id, wallet_id);
CREATE INDEX ix_bucket_account ON balance_buckets (tenant_id, external_account_id, asset_code);

CREATE TABLE bucket_status_events (                               -- immutable lifecycle history
  tenant_id        uuid         NOT NULL,
  bucket_id        uuid         NOT NULL,
  event_seq        integer      NOT NULL CHECK (event_seq >= 1),
  from_status      text,
  to_status        text         NOT NULL CHECK (to_status IN ('ACTIVE','SUSPENDED','FROZEN','CLOSED')),
  reason_code      text         NOT NULL,
  approval_ref     varchar(128),
  idempotency_key  varchar(255) NOT NULL,
  client_id        varchar(255) NOT NULL,
  correlation_id   varchar(128) NOT NULL,
  occurred_at      timestamptz  NOT NULL,
  PRIMARY KEY (tenant_id, bucket_id, event_seq),
  FOREIGN KEY (tenant_id, bucket_id) REFERENCES balance_buckets (tenant_id, bucket_id) ON DELETE RESTRICT
);

-- ============ 4. Balance positions: THE ONLY balance store (ADR 0017) ============
CREATE TABLE balance_projections (
  tenant_id            uuid          NOT NULL REFERENCES tenants ON DELETE RESTRICT,
  position_id          uuid          NOT NULL,
  external_account_id  varchar(128)  NOT NULL,
  asset_code           varchar(16)   NOT NULL REFERENCES assets ON DELETE RESTRICT,
  account_scope        text          NOT NULL CHECK (account_scope IN ('CUSTOMER','SYSTEM')),
  balance_bucket_id    uuid,                                       -- NULL = direct position (system account)
  posted_balance       numeric(38,0) NOT NULL DEFAULT 0,             -- signed, debit-positive (I-9)
  reserved_amount      numeric(38,0) NOT NULL DEFAULT 0 CHECK (reserved_amount >= 0),
  last_position_seq    bigint        NOT NULL DEFAULT 0 CHECK (last_position_seq >= 0),
  active_hold_count    integer       NOT NULL DEFAULT 0 CHECK (active_hold_count >= 0),
  version              bigint        NOT NULL DEFAULT 0,
  created_at           timestamptz   NOT NULL DEFAULT now(),         -- as_of of an empty position
  updated_at           timestamptz   NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, position_id),
  UNIQUE (tenant_id, position_id, external_account_id, asset_code),
  UNIQUE (tenant_id, position_id, balance_bucket_id),
  FOREIGN KEY (tenant_id, balance_bucket_id, external_account_id, asset_code, account_scope)   -- bucket mapping must agree
    REFERENCES balance_buckets (tenant_id, bucket_id, external_account_id, asset_code, account_scope) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, external_account_id, account_scope)
    REFERENCES account_state_cache (tenant_id, external_account_id, account_scope) ON DELETE RESTRICT,
  CHECK (balance_bucket_id IS NOT NULL OR (reserved_amount = 0 AND active_hold_count = 0)),  -- holds only on buckets
  CHECK (balance_bucket_id IS NOT NULL OR account_scope = 'SYSTEM')                          -- direct positions: system only
);
CREATE UNIQUE INDEX ux_pos_bucket ON balance_projections (tenant_id, balance_bucket_id)
  WHERE balance_bucket_id IS NOT NULL;                              -- one position per bucket
CREATE UNIQUE INDEX ux_pos_direct ON balance_projections (tenant_id, external_account_id, asset_code)
  WHERE balance_bucket_id IS NULL;                                  -- one direct position per system account+asset
CREATE INDEX ix_pos_account ON balance_projections (tenant_id, external_account_id, asset_code);
-- Customer balance identity = (tenant_id, external_account_id, asset_code) = exactly one bucket position.
-- Wallet balance = Σ its bucket positions per asset; system account balance = Σ its positions. Neither is stored.

-- ============ 5. Journal (append-only) ============
CREATE TABLE ledger_transactions (
  tenant_id                uuid          NOT NULL REFERENCES tenants ON DELETE RESTRICT,
  transaction_id           uuid          NOT NULL,
  transaction_type         text          NOT NULL CHECK (transaction_type IN
                             ('STANDARD','REVERSAL','REFUND','HOLD_CAPTURE','FX','SETTLEMENT',
                              'ADJUSTMENT','OPENING_BALANCE')),
  origin                   text          NOT NULL CHECK (origin IN ('CLIENT','SYSTEM','EXCEPTIONAL')),  -- set by the server from the token (ADR 0021)
  idempotency_key          varchar(255)  NOT NULL CHECK (idempotency_key ~ '^[\x21-\x7E]{1,255}$'),
  request_hash             bytea         NOT NULL CHECK (octet_length(request_hash) = 32),
  external_ref             varchar(255),
  reverses_transaction_id  uuid,
  related_transaction_id   uuid,
  hold_id                  uuid,
  fx_quote_id              uuid,
  policy_ref               varchar(128),                            -- server-resolved policy that permitted a restricted-state operation
  reason_code              text,
  posting_count            smallint      NOT NULL CHECK (posting_count BETWEEN 2 AND 100),
  effective_at             timestamptz   NOT NULL,
  posted_at                timestamptz   NOT NULL,
  client_id                varchar(255)  NOT NULL,
  request_id               varchar(128)  NOT NULL,
  correlation_id           varchar(128)  NOT NULL,
  approval_ref             varchar(128),
  PRIMARY KEY (tenant_id, transaction_id),
  UNIQUE (tenant_id, idempotency_key),
  FOREIGN KEY (tenant_id, reverses_transaction_id)
    REFERENCES ledger_transactions (tenant_id, transaction_id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, related_transaction_id)
    REFERENCES ledger_transactions (tenant_id, transaction_id) ON DELETE RESTRICT,
  CHECK ((transaction_type = 'REVERSAL')     = (reverses_transaction_id IS NOT NULL)),
  CHECK ((transaction_type = 'HOLD_CAPTURE') = (hold_id IS NOT NULL)),
  CHECK ((transaction_type = 'FX')           = (fx_quote_id IS NOT NULL)),
  CHECK (transaction_type <> 'REFUND' OR related_transaction_id IS NOT NULL),
  CHECK (transaction_type <> 'ADJUSTMENT' OR approval_ref IS NOT NULL),
  CHECK (origin <> 'EXCEPTIONAL' OR (approval_ref IS NOT NULL AND reason_code IS NOT NULL)),
  CHECK (reverses_transaction_id IS DISTINCT FROM transaction_id)
);
CREATE UNIQUE INDEX ux_ltx_single_reversal ON ledger_transactions (tenant_id, reverses_transaction_id)
  WHERE reverses_transaction_id IS NOT NULL;                        -- reversed at most once
CREATE UNIQUE INDEX ux_ltx_fx_quote_once  ON ledger_transactions (tenant_id, fx_quote_id)
  WHERE fx_quote_id IS NOT NULL;                                    -- quotes are single-use (ADR 0025)
CREATE INDEX ix_ltx_hold    ON ledger_transactions (tenant_id, hold_id) WHERE hold_id IS NOT NULL;
CREATE INDEX ix_ltx_related ON ledger_transactions (tenant_id, related_transaction_id)
  WHERE related_transaction_id IS NOT NULL;
CREATE INDEX ix_ltx_extref  ON ledger_transactions (tenant_id, external_ref) WHERE external_ref IS NOT NULL;

CREATE TABLE ledger_postings (
  tenant_id            uuid          NOT NULL,
  posting_id           uuid          NOT NULL,
  transaction_id       uuid          NOT NULL,
  line_no              smallint      NOT NULL CHECK (line_no BETWEEN 1 AND 100),
  position_id          uuid          NOT NULL,
  external_account_id  varchar(128)  NOT NULL,                     -- double-entry account (always set)
  asset_code           varchar(16)   NOT NULL REFERENCES assets ON DELETE RESTRICT,
  balance_bucket_id    uuid,                                       -- set when the position is a bucket
  wallet_id            uuid,
  direction            char(1)       NOT NULL CHECK (direction IN ('D','C')),
  amount               numeric(38,0) NOT NULL CHECK (amount > 0),
  position_seq         bigint        NOT NULL CHECK (position_seq >= 1),        -- gapless per position
  balance_after        numeric(38,0) NOT NULL,                                  -- of the position, signed (I-9)
  account_version      bigint        NOT NULL CHECK (account_version >= 1),     -- exact account snapshot used
  posted_at            timestamptz   NOT NULL,
  effective_at         timestamptz   NOT NULL,
  PRIMARY KEY (tenant_id, posting_id),
  UNIQUE (tenant_id, transaction_id, line_no),
  UNIQUE (tenant_id, position_id, position_seq),
  FOREIGN KEY (tenant_id, transaction_id)
    REFERENCES ledger_transactions (tenant_id, transaction_id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, position_id, external_account_id, asset_code)
    REFERENCES balance_projections (tenant_id, position_id, external_account_id, asset_code) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, position_id, balance_bucket_id)
    REFERENCES balance_projections (tenant_id, position_id, balance_bucket_id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, balance_bucket_id, wallet_id)
    REFERENCES balance_buckets (tenant_id, bucket_id, wallet_id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, external_account_id, account_version)
    REFERENCES account_snapshots (tenant_id, external_account_id, account_version) ON DELETE RESTRICT,
  CHECK ((balance_bucket_id IS NULL) = (wallet_id IS NULL))
);
CREATE INDEX ix_lp_tx      ON ledger_postings (tenant_id, transaction_id);
CREATE INDEX ix_lp_wallet  ON ledger_postings (tenant_id, wallet_id, posted_at, posting_id) WHERE wallet_id IS NOT NULL;
CREATE INDEX ix_lp_account ON ledger_postings (tenant_id, external_account_id, asset_code, posted_at);

-- I-4 in the database: every transaction balances per asset and has exactly posting_count legs.
CREATE FUNCTION ledger.check_transaction_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_expected smallint;
  v_count    int;
  v_bad      text;
BEGIN
  SELECT posting_count INTO v_expected
    FROM ledger.ledger_transactions
   WHERE tenant_id = NEW.tenant_id AND transaction_id = NEW.transaction_id;

  SELECT count(*) INTO v_count
    FROM ledger.ledger_postings
   WHERE tenant_id = NEW.tenant_id AND transaction_id = NEW.transaction_id;

  IF v_count <> v_expected THEN
    RAISE EXCEPTION 'transaction % has % postings, expected %', NEW.transaction_id, v_count, v_expected
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;

  SELECT string_agg(asset_code, ',') INTO v_bad FROM (
    SELECT asset_code
      FROM ledger.ledger_postings
     WHERE tenant_id = NEW.tenant_id AND transaction_id = NEW.transaction_id
     GROUP BY asset_code
    HAVING sum(CASE direction WHEN 'D' THEN amount ELSE -amount END) <> 0) t;

  IF v_bad IS NOT NULL THEN
    RAISE EXCEPTION 'transaction % unbalanced for asset(s) %', NEW.transaction_id, v_bad
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER trg_lp_balanced
  AFTER INSERT ON ledger_postings
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION ledger.check_transaction_balanced();
-- Cost: one check per posting row at COMMIT (≤100 rows/tx). [benchmark] Option: fire only for line_no = 1.

-- Defence in depth for ADR 0021: wallet/bucket state versus origin and direction, and the bucket mapping.
-- ledger-core decides first; a raise here is a bug (INTERNAL). Statuses are read after the position lock ③.
CREATE FUNCTION ledger.guard_posting() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  v_pos_bucket uuid;
  v_wallet     text;
  v_bucket     text;
  v_state      text;
  v_origin     text;
  v_type       text;
  v_policy     text;
  v_normal     char(1);
  v_incoming   boolean;
BEGIN
  SELECT balance_bucket_id INTO v_pos_bucket
    FROM ledger.balance_projections WHERE tenant_id = NEW.tenant_id AND position_id = NEW.position_id;
  IF v_pos_bucket IS DISTINCT FROM NEW.balance_bucket_id THEN
    RAISE EXCEPTION 'posting % does not match the bucket of its position', NEW.posting_id
      USING ERRCODE = 'integrity_constraint_violation';
  END IF;
  IF NEW.balance_bucket_id IS NULL THEN
    RETURN NEW;                                                     -- direct (system) position
  END IF;

  SELECT w.status, b.status INTO v_wallet, v_bucket
    FROM ledger.balance_buckets b JOIN ledger.wallets w ON w.tenant_id = b.tenant_id AND w.wallet_id = b.wallet_id
   WHERE b.tenant_id = NEW.tenant_id AND b.bucket_id = NEW.balance_bucket_id;
  v_state := CASE
    WHEN 'CLOSED' IN (v_wallet, v_bucket) THEN 'CLOSED'
    WHEN v_wallet = 'PROVISIONING'       THEN 'PROVISIONING'
    WHEN 'FROZEN' IN (v_wallet, v_bucket) THEN 'FROZEN'
    WHEN v_wallet = 'CLOSING'            THEN 'CLOSING'
    WHEN 'SUSPENDED' IN (v_wallet, v_bucket) THEN 'SUSPENDED'
    ELSE 'ACTIVE' END;
  IF v_state = 'ACTIVE' THEN
    RETURN NEW;
  END IF;

  SELECT origin, transaction_type, policy_ref INTO v_origin, v_type, v_policy
    FROM ledger.ledger_transactions WHERE tenant_id = NEW.tenant_id AND transaction_id = NEW.transaction_id;
  SELECT normal_side INTO v_normal
    FROM ledger.account_snapshots
   WHERE tenant_id = NEW.tenant_id AND external_account_id = NEW.external_account_id
     AND account_version = NEW.account_version;
  v_incoming := (NEW.direction = v_normal);                         -- increases the presented balance

  IF v_state IN ('CLOSED','PROVISIONING') THEN
    NULL;                                                           -- never
  ELSIF v_origin = 'EXCEPTIONAL' THEN
    RETURN NEW;                                                     -- approved legal/corrective operation
  ELSIF v_state = 'FROZEN' THEN
    NULL;                                                           -- no ordinary debit, credit or capture
  ELSIF v_state = 'CLOSING' THEN
    IF v_origin = 'SYSTEM' THEN RETURN NEW; END IF;                 -- closure workflow
  ELSIF v_state = 'SUSPENDED' THEN
    IF v_incoming AND (v_origin = 'SYSTEM' OR v_policy IS NOT NULL) THEN RETURN NEW; END IF;
    IF NOT v_incoming AND v_type = 'HOLD_CAPTURE' AND v_policy IS NOT NULL THEN RETURN NEW; END IF;
  END IF;
  RAISE EXCEPTION 'posting % refused: % % % posting on a % bucket', NEW.posting_id, v_origin, v_type,
        CASE WHEN v_incoming THEN 'incoming' ELSE 'outgoing' END, v_state
    USING ERRCODE = 'integrity_constraint_violation';
END $$;

CREATE TRIGGER trg_lp_guard BEFORE INSERT ON ledger_postings
  FOR EACH ROW EXECUTE FUNCTION ledger.guard_posting();
-- Cost: three primary-key lookups per posting row. [benchmark]

-- ============ 6. Holds (always on a bucket position) ============
CREATE TABLE holds (                                               -- current state (mutable)
  tenant_id            uuid          NOT NULL REFERENCES tenants ON DELETE RESTRICT,
  hold_id              uuid          NOT NULL,
  position_id          uuid          NOT NULL,
  balance_bucket_id    uuid          NOT NULL,
  wallet_id            uuid          NOT NULL,
  policy_code          varchar(32)   NOT NULL,
  policy_version       integer       NOT NULL,
  amount               numeric(38,0) NOT NULL CHECK (amount > 0),
  captured_amount      numeric(38,0) NOT NULL DEFAULT 0 CHECK (captured_amount >= 0),
  released_amount      numeric(38,0) NOT NULL DEFAULT 0 CHECK (released_amount >= 0),
  status               text          NOT NULL CHECK (status IN ('ACTIVE','CAPTURED','RELEASED','EXPIRED')),
  expires_at           timestamptz   NOT NULL,                      -- ≤ created_at + policy max_ttl (application rule)
  external_ref         varchar(255),
  account_version      bigint        NOT NULL,
  created_at           timestamptz   NOT NULL,
  updated_at           timestamptz   NOT NULL,
  version              bigint        NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id, hold_id),
  FOREIGN KEY (tenant_id, position_id, balance_bucket_id)
    REFERENCES balance_projections (tenant_id, position_id, balance_bucket_id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, balance_bucket_id, wallet_id)
    REFERENCES balance_buckets (tenant_id, bucket_id, wallet_id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, policy_code, policy_version)
    REFERENCES hold_policies (tenant_id, policy_code, policy_version) ON DELETE RESTRICT,
  CHECK (captured_amount + released_amount <= amount),             -- no over-capture, no over-release
  CHECK ((status = 'ACTIVE') = (captured_amount + released_amount < amount)),
  CHECK (expires_at > created_at)
);
CREATE INDEX ix_holds_expiry ON holds (expires_at) WHERE status = 'ACTIVE';     -- expiry worker
CREATE INDEX ix_holds_bucket ON holds (tenant_id, balance_bucket_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_holds_wallet ON holds (tenant_id, wallet_id, status);
CREATE INDEX ix_holds_extref ON holds (tenant_id, external_ref) WHERE external_ref IS NOT NULL;

ALTER TABLE ledger_transactions
  ADD FOREIGN KEY (tenant_id, hold_id) REFERENCES holds (tenant_id, hold_id) ON DELETE RESTRICT;

CREATE TABLE hold_events (                                          -- immutable history
  tenant_id        uuid          NOT NULL,
  hold_id          uuid          NOT NULL,
  event_seq        integer       NOT NULL CHECK (event_seq >= 1),
  event_type       text          NOT NULL CHECK (event_type IN ('CREATED','CAPTURED','RELEASED','EXPIRED')),
  amount           numeric(38,0) NOT NULL CHECK (amount > 0),
  transaction_id   uuid,
  reason           text          CHECK (reason IN ('CLIENT_REQUEST','FINAL_CAPTURE_REMAINDER','EXPIRY','CANCELLED')),
  policy_ref       varchar(128),                                    -- e.g. capture while SUSPENDED
  approval_ref     varchar(128),
  idempotency_key  varchar(255)  NOT NULL,
  client_id        varchar(255)  NOT NULL,
  correlation_id   varchar(128)  NOT NULL,
  occurred_at      timestamptz   NOT NULL,
  PRIMARY KEY (tenant_id, hold_id, event_seq),
  FOREIGN KEY (tenant_id, hold_id) REFERENCES holds (tenant_id, hold_id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, transaction_id) REFERENCES ledger_transactions (tenant_id, transaction_id) ON DELETE RESTRICT,
  CHECK ((event_type = 'CAPTURED') = (transaction_id IS NOT NULL))
);

-- ============ 7. Idempotency ============
CREATE TABLE idempotency_records (                                  -- append-only; one key space per tenant (ADR 0018)
  tenant_id        uuid          NOT NULL REFERENCES tenants ON DELETE RESTRICT,
  idempotency_key  varchar(255)  NOT NULL,
  operation        text          NOT NULL,
  client_id        varchar(255)  NOT NULL,
  hash_version     smallint      NOT NULL CHECK (hash_version >= 1),
  request_hash     bytea         NOT NULL CHECK (octet_length(request_hash) = 32),
  outcome          text          NOT NULL CHECK (outcome IN ('ACCEPTED','REJECTED')),
  resource_type    text,                                             -- transaction, hold, wallet, bucket, …
  resource_id      uuid,
  error_code       text,
  request_id       varchar(128)  NOT NULL,
  correlation_id   varchar(128)  NOT NULL,
  created_at       timestamptz   NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, idempotency_key),
  CHECK ((outcome = 'ACCEPTED') = (resource_id IS NOT NULL AND error_code IS NULL)),
  CHECK ((outcome = 'REJECTED') = (error_code IS NOT NULL))
);

-- ============ 8. FX: execution quotes and valuation rates ============
CREATE TABLE fx_quotes (                                            -- immutable snapshot of the execution quote
  tenant_id       uuid           NOT NULL REFERENCES tenants ON DELETE RESTRICT,
  fx_quote_id     uuid           NOT NULL,
  source_asset    varchar(16)    NOT NULL REFERENCES assets,
  target_asset    varchar(16)    NOT NULL REFERENCES assets,
  rate            numeric(38,18) NOT NULL CHECK (rate > 0),          -- execution rate, target per source, major units
  mid_rate        numeric(38,18) CHECK (mid_rate > 0),
  rate_source     text           NOT NULL,                           -- Treasury / FX provider
  source_ref      varchar(128)   NOT NULL,
  quoted_at       timestamptz    NOT NULL,
  valid_until     timestamptz    NOT NULL,
  rounding_mode   text           NOT NULL CHECK (rounding_mode IN ('HALF_EVEN','HALF_UP','DOWN','UP')),
  source_amount   numeric(38,0)  NOT NULL CHECK (source_amount > 0),
  target_amount   numeric(38,0)  NOT NULL CHECK (target_amount > 0),
  fee_amount      numeric(38,0)  NOT NULL DEFAULT 0 CHECK (fee_amount >= 0),
  fee_asset       varchar(16)    REFERENCES assets,
  spread_amount   numeric(38,0)  NOT NULL DEFAULT 0 CHECK (spread_amount >= 0),
  recorded_at     timestamptz    NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, fx_quote_id),
  CHECK (source_asset <> target_asset),
  CHECK (valid_until > quoted_at),
  CHECK ((fee_amount = 0) OR fee_asset IS NOT NULL)
);
ALTER TABLE ledger_transactions
  ADD FOREIGN KEY (tenant_id, fx_quote_id) REFERENCES fx_quotes (tenant_id, fx_quote_id) ON DELETE RESTRICT;

CREATE TABLE fx_valuation_rates (                                   -- immutable; reports are reproducible as of a date (ADR 0025)
  tenant_id     uuid           NOT NULL REFERENCES tenants ON DELETE RESTRICT,
  rate_source   text           NOT NULL,
  base_asset    varchar(16)    NOT NULL REFERENCES assets,
  quote_asset   varchar(16)    NOT NULL REFERENCES assets,
  as_of         timestamptz    NOT NULL,
  rate          numeric(38,18) NOT NULL CHECK (rate > 0),            -- quote per base, major units
  source_ref    varchar(128)   NOT NULL,
  recorded_at   timestamptz    NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, rate_source, base_asset, quote_asset, as_of),
  CHECK (base_asset <> quote_asset)
);

-- ============ 9. Settlement ============
CREATE TABLE settlement_batches (                                   -- current state (mutable)
  tenant_id             uuid          NOT NULL REFERENCES tenants ON DELETE RESTRICT,
  batch_id              uuid          NOT NULL,
  rail                  text          NOT NULL,
  settlement_ref        varchar(128),
  clearing_account_id   varchar(128)  NOT NULL,                     -- external (system) account
  asset_code            varchar(16)   NOT NULL REFERENCES assets,
  status                text          NOT NULL CHECK (status IN ('OPEN','SUBMITTED','SETTLED','FAILED')),
  expected_total        numeric(38,0) NOT NULL DEFAULT 0 CHECK (expected_total >= 0),
  settled_total         numeric(38,0) CHECK (settled_total >= 0),
  value_date            date,
  created_at            timestamptz   NOT NULL,
  updated_at            timestamptz   NOT NULL,
  version               bigint        NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id, batch_id),
  CHECK (status <> 'SETTLED' OR (settled_total IS NOT NULL AND settlement_ref IS NOT NULL))
);
CREATE UNIQUE INDEX ux_sb_ref ON settlement_batches (tenant_id, rail, settlement_ref) WHERE settlement_ref IS NOT NULL;

CREATE TABLE settlement_batch_items (                               -- insert-only
  tenant_id       uuid NOT NULL,
  batch_id        uuid NOT NULL,
  transaction_id  uuid NOT NULL,
  added_at        timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, batch_id, transaction_id),
  UNIQUE (tenant_id, transaction_id),
  FOREIGN KEY (tenant_id, batch_id) REFERENCES settlement_batches (tenant_id, batch_id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, transaction_id) REFERENCES ledger_transactions (tenant_id, transaction_id) ON DELETE RESTRICT
);

CREATE TABLE settlement_batch_events (                              -- immutable history
  tenant_id       uuid NOT NULL,
  batch_id        uuid NOT NULL,
  event_seq       integer NOT NULL CHECK (event_seq >= 1),
  from_status     text,
  to_status       text NOT NULL,
  transaction_id  uuid,
  detail_code     text,
  occurred_at     timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, batch_id, event_seq),
  FOREIGN KEY (tenant_id, batch_id) REFERENCES settlement_batches (tenant_id, batch_id) ON DELETE RESTRICT
);

-- ============ 10. Reconciliation (never modifies the journal) ============
CREATE TABLE recon_runs (
  tenant_id     uuid NOT NULL REFERENCES tenants ON DELETE RESTRICT,
  run_id        uuid NOT NULL,
  source        text NOT NULL,
  window_start  timestamptz NOT NULL,
  window_end    timestamptz NOT NULL,
  status        text NOT NULL CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
  matched_count bigint, discrepancy_count bigint,
  started_at    timestamptz NOT NULL, finished_at timestamptz,
  event_seq    bigint       NOT NULL DEFAULT 0 CHECK (event_seq >= 0),   -- aggregate sequence of this run's events; the other aggregates use their *_events history tables (ADR 0024 D2)
  PRIMARY KEY (tenant_id, run_id),
  CHECK (window_end > window_start)
);

CREATE TABLE external_statement_lines (                             -- immutable import
  tenant_id       uuid NOT NULL REFERENCES tenants ON DELETE RESTRICT,
  source          text NOT NULL,
  statement_id    varchar(128) NOT NULL,
  line_ref        varchar(128) NOT NULL,
  value_date      date NOT NULL,
  asset_code      varchar(16) NOT NULL REFERENCES assets,
  signed_amount   numeric(38,0) NOT NULL CHECK (signed_amount <> 0),
  external_ref    varchar(255),
  raw_sha256      bytea NOT NULL CHECK (octet_length(raw_sha256) = 32),
  imported_at     timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, source, statement_id, line_ref)
);

CREATE TABLE recon_discrepancies (                                  -- immutable; resolution = new row + adjustment tx
  tenant_id        uuid NOT NULL,
  discrepancy_id   uuid NOT NULL,
  run_id           uuid NOT NULL,
  kind             text NOT NULL CHECK (kind IN ('MISSING_INTERNAL','MISSING_EXTERNAL','AMOUNT_MISMATCH',
                                                 'PROJECTION_DRIFT','SEQ_GAP','UNBALANCED_TX','MAPPING_MISMATCH',
                                                 'HOLD_RESERVE_MISMATCH')),
  subject_ref      varchar(255) NOT NULL,
  expected_amount  numeric(38,0), actual_amount numeric(38,0),
  detected_at      timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, discrepancy_id),
  FOREIGN KEY (tenant_id, run_id) REFERENCES recon_runs (tenant_id, run_id) ON DELETE RESTRICT
);

CREATE TABLE recon_resolutions (
  tenant_id        uuid NOT NULL,
  discrepancy_id   uuid NOT NULL,
  resolution       text NOT NULL CHECK (resolution IN ('ADJUSTED','EXPLAINED','EXTERNAL_FIX','REPAIRED')),
  adjustment_tx_id uuid,
  approval_ref     varchar(128) NOT NULL,
  resolved_at      timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, discrepancy_id),
  FOREIGN KEY (tenant_id, discrepancy_id) REFERENCES recon_discrepancies ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, adjustment_tx_id) REFERENCES ledger_transactions (tenant_id, transaction_id) ON DELETE RESTRICT,
  CHECK ((resolution = 'ADJUSTED') = (adjustment_tx_id IS NOT NULL))
);

CREATE TABLE projection_repairs (                                   -- immutable audit of every approved repair (ADR 0025)
  tenant_id              uuid          NOT NULL,
  repair_id              uuid          NOT NULL,
  position_id            uuid          NOT NULL,
  discrepancy_id         uuid          NOT NULL,
  old_posted_balance     numeric(38,0) NOT NULL,
  old_reserved_amount    numeric(38,0) NOT NULL,
  old_last_position_seq  bigint        NOT NULL,
  old_active_hold_count  integer       NOT NULL,
  new_posted_balance     numeric(38,0) NOT NULL,
  new_reserved_amount    numeric(38,0) NOT NULL,
  new_last_position_seq  bigint        NOT NULL,
  new_active_hold_count  integer       NOT NULL,
  approval_ref           varchar(128)  NOT NULL,
  performed_by           varchar(255)  NOT NULL,
  performed_at           timestamptz   NOT NULL,
  PRIMARY KEY (tenant_id, repair_id),
  FOREIGN KEY (tenant_id, position_id) REFERENCES balance_projections (tenant_id, position_id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, discrepancy_id) REFERENCES recon_discrepancies (tenant_id, discrepancy_id) ON DELETE RESTRICT
);

-- ============ 11. Outbox / Inbox ============
CREATE TABLE outbox_events (
  outbox_id        bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id         uuid         NOT NULL UNIQUE,
  tenant_id        uuid         NOT NULL,
  topic            text         NOT NULL,
  message_key      text         NOT NULL,
  event_type       text         NOT NULL,
  schema_version   smallint     NOT NULL,
  payload          bytea        NOT NULL,                             -- protobuf ledger.events.v1
  headers          jsonb        NOT NULL DEFAULT '{}'::jsonb,
  created_at       timestamptz  NOT NULL DEFAULT now(),
  published_at     timestamptz,
  publish_attempts integer      NOT NULL DEFAULT 0,
  last_error       text         CHECK (last_error ~ '^[A-Za-z0-9_.:-]{1,200}$'),   -- an error class name only, never an exception message (ADR 0024 D7.12)
  first_failed_at  timestamptz,                                       -- set by the relay on the first failed attempt (alerts, quarantine)
  aggregate_type   text         NOT NULL,                             -- ADR 0024 D2
  aggregate_id     text         NOT NULL,
  aggregate_sequence bigint     NOT NULL CHECK (aggregate_sequence >= 1),
  UNIQUE (tenant_id, aggregate_type, aggregate_id, aggregate_sequence),
  CHECK (message_key = tenant_id::text || ':' || aggregate_id)          -- the ordering key is tenant + aggregate
);
CREATE INDEX ix_outbox_pending ON outbox_events (outbox_id) WHERE published_at IS NULL;
CREATE INDEX ix_outbox_published ON outbox_events (published_at) WHERE published_at IS NOT NULL;   -- cleanup scan

-- A blocked event that must be bypassed is NEVER deleted: it is recorded here, append-only, and stays unpublished.
-- Derived state: PENDING = published_at IS NULL and no row here; QUARANTINED = a row here; PUBLISHED = published_at set.
CREATE TABLE outbox_quarantine (
  quarantine_id    bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  outbox_id        bigint       NOT NULL UNIQUE REFERENCES outbox_events (outbox_id) ON DELETE RESTRICT,
  tenant_id        uuid         NOT NULL,                              -- copied from the outbox row by trigger
  message_key      text         NOT NULL,                              -- copied by trigger
  publish_attempts integer      NOT NULL,                              -- copied by trigger
  last_error       text         CHECK (last_error ~ '^[A-Za-z0-9_.:-]{1,200}$'),   -- copied by trigger
  blocked_at       timestamptz  NOT NULL,                              -- when the key started to block (first_failed_at, else created_at)
  reason_code      text         NOT NULL CHECK (reason_code IN ('UNSENDABLE_PAYLOAD','RECORD_TOO_LARGE','TOPIC_CONFIGURATION','AUTHORIZATION','OTHER')),
  reason_text      text         CHECK (length(reason_text) BETWEEN 1 AND 300 AND reason_text ~ '^[\x20-\x7E]+$'),   -- optional; printable ASCII, at most 300; no payload, financial data, customer identifiers, secrets or raw exceptions (ADR 0024 D7.12)
  approval_ref     text         NOT NULL CHECK (approval_ref ~ '^[\x21-\x7E]{1,128}$'),     -- an identifier: printable ASCII, no space
  operator_id      text         NOT NULL CHECK (operator_id ~ '^[\x21-\x7E]{1,128}$'),      -- the human, from the approval workflow; an identifier
  db_role          name         NOT NULL DEFAULT current_user,
  quarantined_at   timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE inbox_events (
  consumer_name    text         NOT NULL,
  message_id       uuid         NOT NULL,
  topic            text         NOT NULL,
  kafka_partition  integer      NOT NULL,
  kafka_offset     bigint       NOT NULL,
  received_at      timestamptz  NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer_name, message_id)
);
CREATE INDEX ix_inbox_received ON inbox_events (received_at);   -- cleanup scan

-- ============ 12. Immutability enforcement (privileges + triggers) ============
CREATE FUNCTION ledger.forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'table % is protected: % refused', TG_TABLE_NAME, TG_OP
    USING ERRCODE = 'insufficient_privilege';
END $$;

CREATE FUNCTION ledger.forbid_identity_change() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE c text;
BEGIN
  FOREACH c IN ARRAY TG_ARGV LOOP
    IF to_jsonb(NEW) -> c IS DISTINCT FROM to_jsonb(OLD) -> c THEN
      RAISE EXCEPTION 'column %.% is immutable', TG_TABLE_NAME, c USING ERRCODE = 'insufficient_privilege';
    END IF;
  END LOOP;
  RETURN NEW;
END $$;

DO $$
DECLARE t text;
BEGIN
  -- append-only: no UPDATE, DELETE, TRUNCATE for anyone (incl. owner) while triggers are enabled
  FOREACH t IN ARRAY ARRAY['assets','hold_policies','account_snapshots','account_asset_snapshots',
                           'wallet_status_events','wallet_provisioning_events','bucket_status_events',
                           'ledger_transactions','ledger_postings','hold_events','idempotency_records',
                           'fx_quotes','fx_valuation_rates','settlement_batch_items','settlement_batch_events',
                           'external_statement_lines','recon_discrepancies','recon_resolutions','projection_repairs','outbox_quarantine']
  LOOP
    EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON ledger.%I
                    FOR EACH ROW EXECUTE FUNCTION ledger.forbid_mutation()', 'trg_'||t||'_no_mutation', t);
    EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON ledger.%I
                    FOR EACH STATEMENT EXECUTE FUNCTION ledger.forbid_mutation()', 'trg_'||t||'_no_truncate', t);
  END LOOP;
  -- mutable state: UPDATE allowed (by grant), DELETE/TRUNCATE never
  FOREACH t IN ARRAY ARRAY['tenant_settings','account_state_cache','account_asset_state','wallets',
                           'wallet_provisioning','balance_buckets','balance_projections','holds',
                           'settlement_batches','recon_runs']
  LOOP
    EXECUTE format('CREATE TRIGGER %I BEFORE DELETE ON ledger.%I
                    FOR EACH ROW EXECUTE FUNCTION ledger.forbid_mutation()', 'trg_'||t||'_no_delete', t);
    EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON ledger.%I
                    FOR EACH STATEMENT EXECUTE FUNCTION ledger.forbid_mutation()', 'trg_'||t||'_no_truncate', t);
  END LOOP;
END $$;

-- outbox/inbox: delivery tables. Unpublished rows are never deleted; a published row never becomes unpublished;
-- nothing truncates them. DELETE of old published rows is granted to ledger_cleanup only.
CREATE FUNCTION ledger.guard_outbox() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    IF OLD.published_at IS NULL THEN
      RAISE EXCEPTION 'outbox row % is not published: DELETE refused', OLD.outbox_id USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN OLD;
  END IF;
  IF OLD.published_at IS NOT NULL AND NEW.published_at IS DISTINCT FROM OLD.published_at THEN
    RAISE EXCEPTION 'outbox row % is published: published_at is set once', OLD.outbox_id USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_outbox_guard BEFORE UPDATE OR DELETE ON outbox_events FOR EACH ROW EXECUTE FUNCTION ledger.guard_outbox();
CREATE TRIGGER trg_outbox_no_truncate BEFORE TRUNCATE ON outbox_events FOR EACH STATEMENT EXECUTE FUNCTION ledger.forbid_mutation();
CREATE TRIGGER trg_inbox_no_truncate  BEFORE TRUNCATE ON inbox_events  FOR EACH STATEMENT EXECUTE FUNCTION ledger.forbid_mutation();

CREATE FUNCTION ledger.guard_outbox_quarantine() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE o ledger.outbox_events%ROWTYPE;
BEGIN
  SELECT * INTO o FROM ledger.outbox_events WHERE outbox_id = NEW.outbox_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'outbox row % does not exist', NEW.outbox_id;
  END IF;
  IF o.published_at IS NOT NULL THEN
    RAISE EXCEPTION 'outbox row % is published: only unpublished rows are quarantined', NEW.outbox_id USING ERRCODE = 'insufficient_privilege';
  END IF;
  NEW.tenant_id := o.tenant_id; NEW.message_key := o.message_key;
  NEW.publish_attempts := o.publish_attempts; NEW.last_error := o.last_error;
  RETURN NEW;
END $$;
CREATE TRIGGER trg_outbox_quarantine_guard BEFORE INSERT ON outbox_quarantine FOR EACH ROW EXECUTE FUNCTION ledger.guard_outbox_quarantine();

CREATE TRIGGER trg_account_cache_identity BEFORE UPDATE ON account_state_cache FOR EACH ROW
  EXECUTE FUNCTION ledger.forbid_identity_change('tenant_id','external_account_id','account_scope');
CREATE TRIGGER trg_wallets_identity BEFORE UPDATE ON wallets FOR EACH ROW
  EXECUTE FUNCTION ledger.forbid_identity_change('tenant_id','wallet_id','owner_ref','wallet_type','created_at');
CREATE TRIGGER trg_buckets_identity BEFORE UPDATE ON balance_buckets FOR EACH ROW
  EXECUTE FUNCTION ledger.forbid_identity_change('tenant_id','bucket_id','wallet_id','asset_code','external_account_id',
                                                 'account_scope','product_code','is_primary','created_at');
CREATE TRIGGER trg_positions_identity BEFORE UPDATE ON balance_projections FOR EACH ROW
  EXECUTE FUNCTION ledger.forbid_identity_change('tenant_id','position_id','external_account_id',
                                                 'asset_code','account_scope','balance_bucket_id','created_at');
CREATE TRIGGER trg_holds_identity BEFORE UPDATE ON holds FOR EACH ROW
  EXECUTE FUNCTION ledger.forbid_identity_change('tenant_id','hold_id','position_id','balance_bucket_id',
                                                 'wallet_id','policy_code','policy_version','amount','expires_at','created_at');

-- ============ 13. Privileges ============
GRANT USAGE ON SCHEMA ledger TO ledger_app, ledger_relay, ledger_cleanup, ledger_readonly, ledger_operator, ledger_repair;

GRANT SELECT ON tenants, assets, tenant_settings, hold_policies TO ledger_app;
GRANT SELECT, INSERT ON account_snapshots, account_asset_snapshots, wallet_status_events, wallet_provisioning_events,
                        bucket_status_events, ledger_transactions, ledger_postings, hold_events, idempotency_records,
                        fx_quotes, fx_valuation_rates, settlement_batch_items, settlement_batch_events,
                        external_statement_lines, recon_discrepancies, recon_resolutions TO ledger_app;
-- mutable state; UPDATE is also what FOR UPDATE / FOR KEY SHARE require on PostgreSQL
GRANT SELECT, INSERT, UPDATE ON account_state_cache, account_asset_state, wallets, wallet_provisioning,
                                balance_buckets, balance_projections, holds, settlement_batches, recon_runs TO ledger_app;
GRANT INSERT ON outbox_events TO ledger_app;            -- identity needs no sequence grant (checked on 18.7)
GRANT SELECT, INSERT ON inbox_events TO ledger_app;

GRANT SELECT, UPDATE (published_at, publish_attempts, last_error, first_failed_at) ON outbox_events TO ledger_relay;   -- publish path
GRANT SELECT ON outbox_quarantine TO ledger_relay;                                                    -- skips quarantined rows
GRANT SELECT ON outbox_events TO ledger_repair;                                                       -- break-glass quarantine (guard trigger reads the row)
GRANT SELECT, INSERT ON outbox_quarantine TO ledger_repair;
GRANT SELECT, DELETE ON outbox_events, inbox_events TO ledger_cleanup;   -- cleanup path only

GRANT SELECT ON ALL TABLES IN SCHEMA ledger TO ledger_readonly;    -- reconciliation runs here (detect-only)

GRANT SELECT, INSERT ON tenants TO ledger_operator;
GRANT UPDATE (status) ON tenants TO ledger_operator;               -- suspend tenant (ADR 0020)
GRANT SELECT ON assets TO ledger_operator;
GRANT SELECT, INSERT ON tenant_settings, hold_policies TO ledger_operator;
GRANT UPDATE (reporting_asset_code, valuation_policy_ref, account_cache_max_age, approval_ref, updated_at)
  ON tenant_settings TO ledger_operator;

GRANT SELECT, UPDATE (posted_balance, reserved_amount, last_position_seq, active_hold_count, version, updated_at)
  ON balance_projections TO ledger_repair;                         -- runbook only (ADR 0025)
GRANT SELECT ON ledger_postings, holds, recon_discrepancies TO ledger_repair;
GRANT SELECT, INSERT ON projection_repairs TO ledger_repair;
-- Superusers bypass grants, and the owner can DISABLE TRIGGER: mitigated by pgaudit on DDL/role
-- changes, a break-glass procedure, and the reconciliation chain checks (§16.3).

-- ============ 14. Tenant isolation: RLS as second layer (ADR 0014) ============
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['tenant_settings','hold_policies','account_state_cache','account_asset_state',
                           'account_snapshots','account_asset_snapshots','wallets','wallet_status_events',
                           'wallet_provisioning','wallet_provisioning_events','balance_buckets','bucket_status_events',
                           'balance_projections','ledger_transactions','ledger_postings','holds','hold_events',
                           'idempotency_records','fx_quotes','fx_valuation_rates','settlement_batches',
                           'settlement_batch_items','settlement_batch_events','recon_runs','external_statement_lines',
                           'recon_discrepancies','recon_resolutions','projection_repairs']
  LOOP
    EXECUTE format('ALTER TABLE ledger.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE ledger.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format($p$CREATE POLICY tenant_isolation ON ledger.%I TO ledger_app, ledger_repair
                      USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
                      WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)$p$, t);
    EXECUTE format('CREATE POLICY readonly_all ON ledger.%I FOR SELECT TO ledger_readonly USING (true)', t);
    -- FORCE applies to the owner too; without this, data migrations would silently see zero rows.
    EXECUTE format('CREATE POLICY owner_all ON ledger.%I TO ledger_owner USING (true) WITH CHECK (true)', t);
  END LOOP;
  FOREACH t IN ARRAY ARRAY['tenant_settings','hold_policies']
  LOOP
    EXECUTE format('CREATE POLICY operator_all ON ledger.%I TO ledger_operator USING (true) WITH CHECK (true)', t);
  END LOOP;
END $$;
-- Cross-tenant workers (hold expiry, provisioning) iterate `tenants` (no RLS) and set app.tenant_id per
-- tenant, one tenant per transaction. The outbox/inbox tables carry no RLS; the relay is tenant-agnostic.
```

**نکته‌ها دربارهٔ DDL**

- **فهرست جدول‌ها (32 جدول)**: `tenants`، `assets`، `tenant_settings`، `hold_policies`،
  `account_state_cache`، `account_asset_state`، `account_snapshots`، `account_asset_snapshots`، `wallets`،
  `wallet_status_events`، `wallet_provisioning`، `wallet_provisioning_events`، `balance_buckets`،
  `bucket_status_events`، `balance_projections`، `ledger_transactions`، `ledger_postings`، `holds`، `hold_events`،
  `idempotency_records`، `fx_quotes`، `fx_valuation_rates`، `settlement_batches`، `settlement_batch_items`،
  `settlement_batch_events`، `recon_runs`، `external_statement_lines`، `recon_discrepancies`،
  `recon_resolutions`، `projection_repairs`، `outbox_events`، `inbox_events`.
- قاعدهٔ «هر تراکنش حداکثر یک بار reverse می‌شود» با partial unique index اجرا می‌شود. جدول پیوندی
  پیش‌نویس old 0016 لازم نیست، چون آن جدول فقط راه دور زدن رفتار Oracle بود.
- این‌ها در DB **enforce نمی‌شوند** و به `ledger-core` و reconciliation سپرده شده‌اند: reverse کردن یک
  reversal، درستی زنجیرهٔ `balance_after`، فرمول موجودی آزاد و سقف اضافه‌برداشت (S09)، انقضای hold در محدودهٔ
  `max_ttl` و منع hold تازه در Wallet معلق. trigger `trg_lp_guard` نگاشت Bucket و ماتریس وضعیت posting
  (ADR 0021) را در DB دوباره بررسی می‌کند.
- **Partitioning** [مسدود: اطلاعات]: partition زمانی، uniqueهای سراسری مثل `(position_id, position_seq)`
  و کلید idempotency را **می‌شکند**. journal هم حذف نمی‌شود. اگر partition لازم شد، روش درست
  `PARTITION BY HASH (tenant_id)` است. partition زمانی فقط برای outbox، inbox و جدول‌های timeline در
  Payments مناسب است.
- **Retention**: journal، رویدادهای وضعیت، `hold_events`، رکوردهای idempotency، `fx_quotes`، نرخ‌های
  ارزش‌گذاری و settlement تا تعیین نگهداشت قانونی حذف نمی‌شوند (باز، §20.1). رویدادهای منتشرشدهٔ outbox را یک job جدا با نقش `ledger_cleanup` حذف می‌کند: ردیف‌های منتشرشده‌ای که از `outbox_retention` قدیمی‌ترند، و ردیف‌های inbox که از `inbox_retention` قدیمی‌ترند، هر بار در دسته‌های کوچک. حذف ناموفق در اجرای بعدی دوباره امتحان می‌شود. ردیف منتشرنشده هرگز حذف نمی‌شود. زنجیرهٔ نگهداشت (پیشنهاد): `Kafka retention ≤ Outbox retention ≤ Inbox retention`، یعنی `7 ≤ 14 ≤ 30` روز، با مالکیت Platform (topic)، تیم Ledger (outbox) و هر تیم مصرف‌کننده (inbox). inbox باید از outbox حداقل به‌اندازهٔ بیشترین lag مصرف‌کننده بلندتر باشد (استنتاج). بایگانی پیش از حذف در v1 لازم نیست (استنتاج: رویدادها از جدول‌های نگه‌داشته‌شدهٔ journal، وضعیت، hold، FX، settlement و provisioning بازسازی می‌شوند؛ یک تست باید این را تأیید کند). بایگانی برای الزام قانونی با مالکیت compliance و گزارش‌دهی (همراه حقوقی و DPO) است، جدا از پاکسازی عملیاتی؛ نگه‌داشتن ردیف‌های outbox آن را برآورده نمی‌کند. پاکسازی فقط جدول‌های تحویل را محدود می‌کند؛ سابقهٔ حسابرسی، journal، جدول‌های رویداد و وضعیت و رکوردهای idempotency است (ADR 0024 D7).

---

## 8. Financial Posting, Wallet and Hold Flows

### 8.1 قواعد مشترک

**ترتیب قفل سراسری** [توجیه فنی]. هر مسیر فقط زیرمجموعه‌ای از این ترتیب را می‌گیرد و هیچ مسیری قفلی از
سطح پایین‌تر را پیش از سطح بالاتر نمی‌گیرد:

| سطح | ردیف | نوع قفل | ترتیب درون سطح |
|---|---|---|---|
| ① | `account_state_cache` (anchor Account بیرونی) | posting، hold، ایجاد Bucket: `FOR KEY SHARE`. اعمال وضعیت Account: `FOR UPDATE` | `external_account_id` با `COLLATE "C"` |
| ② | `wallets` | فقط عملیات lifecycle Wallet و Bucket: `FOR UPDATE` | `wallet_id` |
| ③ | `balance_projections` (positionها) | `FOR UPDATE` | `position_id` (ترتیب بایتی UUID) |
| ④ | `holds` | `FOR UPDATE` | `hold_id` |
| ⑤ | `settlement_batches` | `FOR UPDATE` | `batch_id` |

- UPDATE روی `wallets` و `balance_buckets` (وضعیت) فقط **پس از** گرفتن قفل‌های ② و ③ انجام می‌شود.
- postingها ردیف Wallet یا Bucket را قفل **نمی‌کنند**. وضعیت را **پس از** گرفتن قفل position می‌خوانند.
  چون هر تغییر وضعیت Wallet یا Bucket قفل همهٔ positionهای مربوط را نگه می‌دارد، posting یا پیش از آن
  commit شده یا وضعیت جدید را می‌بیند. **این خطی‌سازی کاملاً محلی است** (§9.3).
- deadlock (`40P01`) به `CONTENTION` نگاشت می‌شود. ترتیب قفل برای اجتناب از deadlock است، نه برای
  درستی.

**مانده‌ها**: `posted_balance` علامت‌دار است و جهت debit را مثبت می‌گیرد (I-9). مقدار ارائه‌شده با
`normal_side` حساب بیرونی تعیین می‌شود (از snapshot). موجودی آزاد position برابر مقدار ارائه‌شده منهای
`reserved_amount` است. **ماندهٔ Wallet** برابر Σ positionهای Bucketهای آن برای هر asset است. **ماندهٔ
Account** برابر Σ positionهایی است که همان `external_account_id` و asset را دارند. هیچ‌کدام ذخیره
نمی‌شود.

**قواعد پذیرش posting**

| بُعد | قاعده | مالک قاعده |
|---|---|---|
| Account بیرونی | `can_debit` یا `can_credit` متناسب با جهت. asset در `allowed_assets`. `closed=false` | Account Service (از cache) |
| اضافه‌برداشت | موجودی آزاد مؤثر = ارائه‌شده (با `normal_side` از snapshot) + سقف اضافه‌برداشت همان asset (از snapshot نسخه‌دار، پیش‌فرض 0) − `reserved_amount`، پس از آخرین posting همان position در تراکنش، باید ≥ 0 باشد. سقف را فقط Account Service تعیین می‌کند و هیچ درخواستی آن را نمی‌دهد یا بالا نمی‌برد؛ کاهش سقف از راه fence می‌آید. اضافه‌برداشت عادی مجوز short-selling رمزارز یا margin اوراق نیست (خارج از محدوده) | Account Service (سیاست)، Ledger (اجرای اتمیک) |
| Wallet و Bucket | ماتریس وضعیت ADR 0021، روی سخت‌گیرانه‌ترین وضعیت Wallet و Bucket: `PROVISIONING` و `CLOSED` هیچ posting نمی‌پذیرند؛ `FROZEN` فقط `EXCEPTIONAL` با `approval_ref`؛ `CLOSING` فقط `SYSTEM` یا `EXCEPTIONAL`؛ `SUSPENDED` برداشت مشتری و hold تازه را رد می‌کند و واریز `SYSTEM` یا واریز مجاز با `policy_ref` را می‌پذیرد. origin از scope توکن می‌آید و هرگز فیلد درخواست نیست. trigger `trg_lp_guard` در DB دوباره بررسی می‌کند (S10، S11، S13) | Ledger |
| Bucket | asset posting باید برابر asset Bucket باشد | Ledger |

### 8.2 PostTransaction: مسیر دقیق نوشتن

هدف هر posting در درخواست یکی از این سه است:

- `balance_bucket_id`
- `wallet_id` با asset، که به Bucket اصلی تبدیل می‌شود (رفتار `BalanceBucketResolver` در Sarv)
- `external_account_id` با asset، برای حساب سیستمی، که به position مستقیم تبدیل می‌شود

```
── خارج از تراکنش DB ─────────────────────────────────────────────
 1. mTLS + JWT → tenant → scope transactions:write و origin از scope (ADR 0021) → rate limit → per-tenant cap
 2. اعتبار شکل و قواعد payload-only (I-1..I-4, I-21, I-22، سقف asset)
 3. request_hash = SHA-256(canonical v1) در ledger-application (ADR 0018)
 4. resolve اهداف (خواندن بدون قفل): bucket → (wallet_id, asset, external_account_id, position_id);
    wallet+asset → bucket اصلی؛ system → position مستقیم (اگر نبود، در مرحلهٔ 9 ساخته می‌شود)
 5. AccountResolver روی external_account_idهای متمایز: cache؛ نبود یا کهنه → یک BatchGetAccounts gRPC
    با deadline محدود → upsert snapshot (فقط نسخهٔ بزرگ‌تر). هیچ فراخوانی شبکه بعد از BEGIN نیست.
    مدخل تازه‌تر از tenant_settings.account_cache_max_age (اولیه 30s) مستقیم استفاده می‌شود؛ شکست
    تازه‌سازی → UNAVAILABLE (fail closed، ADR 0022).
── تراکنش DB (READ COMMITTED) ─────────────────────────────────────
 6. BEGIN; SET LOCAL lock_timeout, statement_timeout, transaction_timeout (ADR 0017);
    SELECT set_config('app.tenant_id', :tenant, true);
 7. SELECT pg_advisory_xact_lock(:key64)        -- key64 = 8 بایت اول SHA-256(tenant ‖ key)، در Java
    SELECT … FROM idempotency_records WHERE …  → اگر موجود باشد: replay یا conflict، سپس COMMIT. پایان.
 8. ① anchors: SELECT … FROM account_state_cache WHERE … FOR KEY SHARE   (مرتب)
      نبود → ACCOUNT_UNKNOWN (رد ثبت‌شده)
 9. position مستقیمِ ناموجود: INSERT INTO balance_projections (…) VALUES (…)
      ON CONFLICT (tenant_id, external_account_id, asset_code) WHERE balance_bucket_id IS NULL DO NOTHING;
      سپس position_id خوانده می‌شود
10. ③ SELECT posted_balance, reserved_amount, last_position_seq FROM balance_projections
      WHERE tenant_id=:t AND position_id = :p FOR UPDATE                    (مرتب بر اساس position_id)
11. خواندن وضعیت wallets و balance_buckets مربوط (بدون قفل؛ پس از ③ دیده‌شدن آخرین وضعیت تضمین است)
12. ledger-core: قواعد §8.1، I-5، I-27، سقف asset. رد ثبت‌شدنی → فقط مرحلهٔ 16 با REJECTED، سپس COMMIT
13. INSERT ledger_transactions; INSERT ledger_postings (position_seq = last+1..، balance_after زنجیره‌ای،
      account_version از snapshot)
14. UPDATE balance_projections SET posted_balance, last_position_seq, version=version+1, updated_at=now()
15. INSERT outbox_events (TransactionPosted؛ و در صورت فعال بودن، PositionActivity برای هر position)
16. INSERT idempotency_records (ACCEPTED | REJECTED)                        -- آخرین دستور
17. COMMIT → constraint trigger I-4؛ شکست → rollback و INTERNAL (باگ)
── پس از COMMIT ───────────────────────────────────────────────────
18. پاسخ فقط پس از commit. اگر ارتباط پس از commit قطع شود، کلاینت با همان کلید retry می‌کند و replay
    می‌گیرد.
```

نگاشت خطا: `55P03`، `57014` و `40P01` به **`CONTENTION`** نگاشت می‌شوند. نقض unique روی `position_seq`
یا شکست trigger در I-4 یک باگ است و به **`INTERNAL`** نگاشت می‌شود. در PostgreSQL هر خطا کل تراکنش را
abort می‌کند، پس ادامه پس از خطا ممکن نیست.

### 8.3 ReverseTransaction

1. اصل تراکنش بدون قفل خوانده می‌شود، چون تغییرناپذیر است. اگر نوع آن `REVERSAL` باشد،
   `TRANSACTION_NOT_REVERSIBLE` برمی‌گردد.
2. postingهای قرینه روی **همان positionها** ساخته می‌شوند: همان Bucket یا position مستقیم، با جهت
   برعکس. سپس مراحل 6 تا 18 اجرا می‌شوند. پس از گرفتن قفل‌ها، وجود reversal قبلی بررسی می‌شود
   (`TRANSACTION_ALREADY_REVERSED`) و `ux_ltx_single_reversal` پشتوانهٔ این بررسی است.
3. reversal **معافیت ندارد**. اگر Wallet یا Bucket بسته باشد، Account بیرونی بسته یا freeze شده باشد، یا
   موجودی کافی نباشد، رد می‌شود. اصلاح با `ADJUSTMENT` همراه با `approval_ref` انجام می‌شود.
4. reversal جزئی وجود ندارد. بازپرداخت جزئی یک تراکنش `REFUND` با `related_transaction_id` است. سقف
   مجموع refundها سیاست Payments است (سیاست Payments).
5. reverse کردن reversal ممنوع است. reversal همزمان روی همان positionها سریالی می‌شود و unique index
   پشتوانهٔ نهایی است.

### 8.4 چرخهٔ عمر Wallet و Bucket (محلی در Ledger)

| عملیات | پیش‌شرط‌ها | قفل‌ها و اثر |
|---|---|---|
| **CreateWallet** (درخواست مورد اعتماد: `owner_ref`، `wallet_type`، assetها) | سقف Wallet برای هر مالک فقط سیاست کسب‌وکار است (`WALLET_LIMIT_EXCEEDED`) | INSERT Wallet در `PROVISIONING`، رویداد وضعیت، ردیف `wallet_provisioning` با `account_request_key`، idempotency، `WalletProvisioningStarted`. سپس saga |
| **Provisioning saga** (ADR 0021) | خارج از هر تراکنش DB: `ProvisionAccounts(account_request_key, owner_ref, wallet_id, assets)` روی Account Service، idempotent روی کلید | تراکنش نگاشت: upsert cache و snapshot → ① anchorها → ② Wallet → INSERT Bucketها و positionها (unique مشتری، FK asset و scope). اگر همهٔ assetها نگاشت شدند Wallet → `ACTIVE` و `WalletActivated`. retry با همان کلید؛ حساب مشتری‌ای که به Wallet دیگر بسته است → `ACCOUNT_ALREADY_BOUND` (نهایی). شکست نهایی → `FAILED`، Wallet → `CLOSED` با `PROVISIONING_FAILED`، و رویداد برای Account Service. بدون 2PC |
| **AddBalanceBucket** (asset تازه) | Wallet `ACTIVE`؛ شناسهٔ حساب فقط از Account Service | همان گام‌های saga برای یک asset |
| **Suspend / Activate Wallet** | `ACTIVE` ↔ `SUSPENDED` | ② Wallet → ③ همهٔ positionهای آن (مرتب) → UPDATE و رویداد |
| **Freeze / Unfreeze Wallet** | scope `wallets:freeze` و `approval_ref` | ② → ③ → UPDATE و رویداد با `approval_ref` |
| **StartClosing / CloseWallet** | بستن: همهٔ positionها صفر و بدون hold، وگرنه `WALLET_BALANCE_NOT_ZERO`. `CLOSED` پایانی است | ② → ③ همه → بستن Bucketها و Wallet، `WalletClosed` |
| **Suspend / Close BalanceBucket** | بستن: `posted_balance = 0`، `reserved_amount = 0`، `active_hold_count = 0`. Bucket اصلی تا Wallet باز است بسته نمی‌شود، مگر با seal حساب (§9.2) | ② Wallet → ③ position → UPDATE و رویداد |

- Wallet بسته باز نمی‌شود. برای همان مالک می‌توان Wallet تازه ساخت، ولی Bucket مشتری تازه به شناسهٔ حساب
  تازه نیاز دارد، چون هر `(حساب، asset)` مشتری برای همیشه به یک Bucket تعلق دارد.
- محدودیت یک مشتری یا محلی است (`SUSPENDED` یا `FROZEN` در Wallet، بدون race) یا بیرونی (حساب، با fence).

### 8.5 چرخهٔ عمر Hold (روی Bucket)

```
           Create (reserved += a)
   ──────────────▶ ACTIVE ──Capture(x, final=false)──▶ ACTIVE (remaining −= x)
                    │  ├─Capture(x, final=true)──▶ CAPTURED   (remainder released)
                    │  ├─Release(y) partial──────▶ ACTIVE
                    │  ├─Release(all)/Cancel─────▶ RELEASED
                    │  └─expires_at ≤ now────────▶ EXPIRED    (worker or lazy check)
```

| عملیات | قفل‌ها | اثر در یک تراکنش DB |
|---|---|---|
| **CreateHold** (Bucket یا Wallet با asset، مبلغ، `policy_code`، انقضا ≤ `max_ttl`) | ① anchor Account Bucket → ③ position Bucket | Wallet و Bucket باید ACTIVE باشند و Account باید `can_debit` داشته باشد. شرط `available ≥ amount`. INSERT در `holds` و رویداد `CREATED`. `reserved += amount` و `active_hold_count += 1`. outbox و idempotency |
| **CaptureHold** (x، اهداف مقصد، final) | ① anchors همهٔ Accountها → ③ positionها → ④ hold | hold باید ACTIVE باشد و `now < expires_at` (`HOLD_EXPIRED`). شرط `x ≤ remaining` (`HOLD_AMOUNT_EXCEEDED`؛ over-capture رد می‌شود). تراکنش `HOLD_CAPTURE` ساخته می‌شود: بدهکار position Bucket، بستانکار مقصد. سپس `reserved −= x` و `captured += x`. اگر final باشد، باقیمانده آزاد می‌شود. رویدادها و outbox |
| **ReleaseHold** (y یا کل) | ③ position → ④ hold | `released += y` و `reserved −= y` |
| **ExpireHolds** (worker، برای هر tenant) | نامزدها بدون قفل از `ix_holds_expiry`. برای هر hold: ③ position → ④ hold، سپس بررسی دوباره | باقیمانده آزاد و رویداد `EXPIRED` ثبت می‌شود. کلید `system:expire:{hold_id}` |

- ساختن hold **posting نمی‌سازد**. فقط capture تراکنش journal می‌سازد. تاریخچهٔ کامل در `hold_events`
  است.
- **سازگاری**: هر تغییر در `reserved_amount` یا `posted_balance` زیر قفل همان position است. ناوردایی که
  reconciliation بررسی می‌کند: `reserved_amount` برابر Σ باقیماندهٔ holdهای ACTIVE همان Bucket است و
  `active_hold_count` با شمار آن‌ها برابر است. CHECK روی `balance_projections` hold روی position مستقیم
  را ممنوع می‌کند.
- **مقایسه با Sarv**: در Sarv فقط capture کامل ممکن است، هر `LockedBalance` غیرصفر همهٔ برداشت‌های دیگر
  را مسدود می‌کند، و reservation تاریخچهٔ داخل Ledger ندارد. این طرح هر سه را برطرف می‌کند.
- **Hold و وضعیت** [تصمیم مالک]: release همیشه ممکن است. capture در `SUSPENDED` فقط وقتی policy همان hold
  (`capture_when_suspended`) اجازه دهد؛ در `FROZEN` هرگز capture عادی (فقط `EXCEPTIONAL`). hold تازه در
  `SUSPENDED` یا `FROZEN` یا با حساب freeze‌شده رد می‌شود. hold هرگز به دلیل نامعلوم بودن نتیجهٔ provider
  آزاد نمی‌شود. TTL پیش‌فرض و بیشینه از `hold_policies` نسخه‌دار می‌آید و هیچ TTL سراسری در کد نیست؛ مقادیر
  پیش از تولید تأیید می‌شوند (باز، §20.1).

---

## 9. External Account Integration

این بخش **فقط درباره Account بیرونی است**. Wallet و Bucket محلی هستند و در §8.4 آمده‌اند.

### 9.1 گزینه‌های اعتبارسنجی

| روش | مزیت | عیب |
|---|---|---|
| فراخوانی همگام در هر posting | همیشه تازه است | latency و وابستگی دسترس‌پذیری. **زیر قفل ممنوع است** |
| فقط projection محلی که با رویداد تغذیه می‌شود | بدون شبکه | lag. Account تازه ممکن است هنوز نرسیده باشد |
| **ترکیبی (توصیه)** | cache-hit بدون شبکه است. cache-miss با یک batch call **پیش از** BEGIN حل می‌شود | race باقیمانده (§9.3) |

### 9.2 طرح پیشنهادی

1. **رویداد** [تصمیم مالک]: Account Service رویداد `AccountStateChanged` را منتشر می‌کند با `tenant_id`،
   `external_account_id`، `account_version` یکنواخت صعودی، وضعیت، مجوزهای debit و credit، assetهای مجاز با
   مجوز و سقف اضافه‌برداشت هر asset، normal side، طبقه‌بندی (`account_scope` و `account_class`)، شناسه و زمان
   رویداد، با کلید `{tenant}:{account_id}`. Ledger در یک تراکنش: ثبت inbox، `FOR UPDATE` روی ردیف cache،
   INSERT snapshot برای نسخهٔ تازه، و UPDATE cache **فقط اگر نسخه بزرگ‌تر باشد**. رویداد تکراری یا قدیمی‌تر
   وضعیت را عقب نمی‌برد.
2. **تازگی و Lookup دسته‌ای** [تصمیم مالک]: مدخل تازه‌تر از `tenant_settings.account_cache_max_age` (اولیه 30 ثانیه،
   قابل پیکربندی، SLO اثبات‌شده نیست) مستقیم استفاده می‌شود. مدخل کهنه یا ناموجود با یک `BatchGetAccounts(ids[])`
   با deadline کوتاه **پیش از BEGIN** تازه می‌شود. اگر تازه‌سازی لازم باشد و Account Service در دسترس نباشد،
   عملیات **fail closed** می‌شود (`UNAVAILABLE`). هرگز در حال نگه‌داشتن قفل مالی Account Service صدا زده
   نمی‌شود. موفقیت lookup اعتبار حساب در لحظهٔ commit را تضمین نمی‌کند؛ اعتبارسنجی روی ردیف خوانده‌شده زیر
   anchor انجام می‌شود.
3. **Anchor**: هر عملیاتی که به Account تکیه دارد (posting، hold، ایجاد Bucket) روی ردیف cache آن قفل
   `FOR KEY SHARE` می‌گیرد. اعمال وضعیت Account با `FOR UPDATE` انجام می‌شود. پس **در Ledger** تغییر وضعیت
   Account با postingها خطی‌سازی می‌شود. [بنچمارک]: حساب‌های مشتری تجمیعی نیستند (§4.2)، ولی حساب سیستمی داغ
   (مثل fee) همهٔ postingها را روی **یک** ردیف قفل shared می‌گیرد و MultiXact hot-spot می‌سازد. جایگزین:
   `pg_advisory_xact_lock_shared` برای posting و `pg_advisory_xact_lock` برای fence.
4. **Fence (`ApplyAccountState`)** [تصمیم مالک]: Account Service برای freeze، close و **هر تغییر محدودکننده** (حذف
   مجوز debit یا credit، حذف asset، کاهش سقف اضافه‌برداشت) **پیش از** اعلام موفقیت این RPC را صدا می‌زند. Ledger
   زیر قفل ① `FOR UPDATE` snapshot را ثبت و cache را به‌روز می‌کند. RPC با `account_version` idempotent است و
   نسخهٔ کهنه `ACCOUNT_STATE_STALE` می‌گیرد. تغییرهای گشاینده می‌توانند فقط با رویداد بیایند. 2PC رد شده است.
5. **Seal برای close Account** [تصمیم مالک]: close از مسیر `ApplyAccountState` می‌رود: ① `FOR UPDATE` → ③ همهٔ
   positionهای این Account (مرتب). برای هر asset: `posted_balance = 0`، `reserved_amount = 0`،
   `active_hold_count = 0`، و هیچ محدودیت مالی باز (Bucket منجمد، آیتم settlement تسویه‌نشده، یا discrepancy باز)
   نمانده باشد؛ وگرنه `ACCOUNT_BALANCE_NOT_ZERO` یا `ACCOUNT_HAS_RESTRICTIONS`. در صورت موفقیت، Bucketهای این
   Account در همان تراکنش بسته می‌شوند (§20.4). اگر seal شکست بخورد، Account Service نباید `CLOSED` اعلام کند.
   چون ماندهٔ مشتری تجمیعی نیست، seal یک حساب مشتری برای هر asset فقط یک position را قفل می‌کند.
6. **نسخه، توکن و lease**: نسخهٔ Account **لازم است**. با آن کهنگی تشخیص داده می‌شود و در هر posting
   (`account_version`) و hold ثبت می‌شود. validation token **رد می‌شود**، چون race را فقط به زمان انقضا
   منتقل می‌کند. operation lease هم **رد می‌شود**، چون یک قفل توزیع‌شده با timeout است و پیچیدگی آن
   متناسب با سودش نیست.
7. **Snapshot برای audit**: `account_snapshots` تغییرناپذیر است و PII ندارد. برای هر posting می‌توان
   نشان داد با کدام طبقه‌بندی و سیاست پذیرفته شده است.

### 9.3 چه چیزی محلی خطی‌سازی می‌شود و چه چیزی توزیع‌شده می‌ماند

| رویداد | ماهیت | تضمین |
|---|---|---|
| تعلیق یا فعال‌سازی یا بستن **Wallet** | **محلی** | قفل ② و ③. posting یا پیش از تغییر commit شده یا وضعیت جدید را می‌بیند. **race وجود ندارد** و fence لازم نیست |
| تعلیق یا بستن **Bucket**، ایجاد Bucket | **محلی** | همان |
| بستن Wallet یا Bucket با «موجودی صفر و بدون hold» | **محلی** | بررسی زیر قفل همهٔ positionهای مربوط |
| **freeze** یا محدودیت Account بیرونی | **توزیع‌شده** | پس از commit شدن fence در Ledger، هیچ posting ناسازگاری commit نمی‌شود. **race باقیمانده** این است: Account Service در T0 تصمیم می‌گیرد و fence در T1 commit می‌شود. postingهای بین T0 و T1 پذیرفته شده‌اند. بدون fence همگام، پنجره به اندازهٔ lag کامل رویداد است |
| **close** Account بیرونی | توزیع‌شده، ولی **قطعی** از راه seal | seal یا موفق است (و پس از آن چیزی post نمی‌شود) یا رد می‌شود. Account Service فقط پس از seal موفق close را نهایی می‌کند |
| تغییر طبقه‌بندی، normal side یا سیاست اضافه‌برداشت | توزیع‌شده | از نسخهٔ بعدی اعمال می‌شود. postingهای قبلی با نسخهٔ قبلی ثبت شده‌اند |

- **مالکیت race باقیمانده**: Account Service مالک تصمیم lifecycle Account و مسئول فراخوانی fence و
  seal است. Ledger تضمین می‌دهد که پس از fence چیزی برخلاف آن commit نشود. معنای «postingهای پیش از
  fence معتبرند» باید در قرارداد هر دو سرویس نوشته شود ([تصمیم مالک]: مالکیت race باقیمانده با Account Service است).
- **Fence ناموفق**: Account Service نباید freeze یا close را کامل اعلام کند. باید retry کند، یا وضعیت
  `PENDING_FREEZE` و `PENDING_CLOSE` را نشان دهد.
- **پیامد نگاشت مشتری** (بحث omnibus در نسخهٔ پیشین تاریخی است): محدودیت سطح مشتری یا بیرونی است (حساب،
  با fence) یا محلی (`SUSPENDED` یا `FROZEN` در Wallet). freeze یک حساب مشتری فقط همان مشتری را متوقف
  می‌کند.

---

## 10. Payment Monitoring and Traceability

### 10.1 آنچه امروز در Sarv هست (شواهد)

| قابلیت | وضعیت | شاهد |
|---|---|---|
| لاگ HTTP خام | هست، ولی **best-effort**. وقتی buffer پر شود یا DB در دسترس نباشد، ردیف‌ها دور ریخته می‌شوند | `docs/http-logging.md` §2 و §8 |
| بدنهٔ درخواست و پاسخ | به‌طور پیش‌فرض **خاموش** است. حداکثر 2000 کاراکتر. query string ذخیره نمی‌شود | `docs/http-logging.md` §2 و §4 |
| پیوند لاگ HTTP با پرداخت | **فقط** با `CorrelationId`. شناسهٔ پرداخت، شمارهٔ تلاش و کد کسب‌وکار provider ثبت نمی‌شود. `ClientName` پر نمی‌شود («known gap») | `docs/http-logging.md` §7 و §9 |
| نگهداری | 30 روز با DELETE دسته‌ای | `docs/http-logging.md` §5 |
| Business/Audit log | امضاشده با HMAC، append-only، at-least-once و idempotent روی `EventId`، در DB جداگانه | `docs/audit-logging.md` §1 تا §5 |
| وضعیت پرداخت | یک فیلد `Status` که وضعیت پرداخت، بانک و Ledger را با هم مخلوط می‌کند: `BankPending`، `NeedsReconciliation`. `FailureReason` در هر تلاش reconciliation **بازنویسی** می‌شود | `PaymentIntent.cs`، `PaymentStatus.cs` |
| ابهام timeout | `PaymentErrors.ProviderOutcomeUnknown` وجود دارد. پس مفهوم ابهام شناخته شده است | `PaymentIntent.RecordExternalReference` |

**نتیجه**: Sarv بلوک‌های سازندهٔ خوبی دارد (correlation، business log امضاشده، redaction متمرکز در
`SensitiveFieldGuard`)، ولی **Payment Timeline دقیق و بادوام ندارد**.

### 10.2 مالکیت و مرز

| داده | مالک | ذخیره‌گاه |
|---|---|---|
| پرداخت، وضعیت‌ها، تلاش‌های provider، callbackها | **Payments/Integration** | DB سرویس Payments |
| تراکنش، hold، settlement و reconciliation مالی | **Ledger** | DB سرویس Ledger. از طریق رویداد و API در دسترس است |
| Timeline تجمیعی برای پشتیبانی | **Operations read model** | DB یا schema جداگانه که از Kafka تغذیه می‌شود. فقط خواندنی است. قابل بازسازی است |

Ledger هیچ داده‌ای دربارهٔ provider، HTTP یا callback نگه **نمی‌دارد**. Payments در هر فراخوانی Ledger
`external_ref = payment_id` و `x-correlation-id` را می‌فرستد. read model دو منبع را با این دو کلید و
`ledger_transaction_id` و `hold_id` به هم وصل می‌کند.

### 10.3 مدل داده (در سرویس Payments، طرح مفهومی)

```sql
-- Payments DB (not Ledger). Status dimensions are separate columns, never one field.
CREATE TABLE payments (
  tenant_id uuid, payment_id uuid, external_ref varchar(255), customer_ref varchar(128),
  amount numeric(38,0), asset_code varchar(16),
  payment_status   text,  -- CREATED, VALIDATED, AUTHORIZED, SUBMITTED, COMPLETED, FAILED, REFUNDED, NEEDS_REVIEW
  provider_status  text,  -- last provider business status, verbatim code mapped to enum + raw code column
  ledger_status    text,  -- NONE, HELD, CAPTURED, POSTED, REVERSED (derived from Ledger events)
  outcome_certainty text CHECK (outcome_certainty IN ('CONFIRMED','UNKNOWN')),
  idempotency_key varchar(255), correlation_id varchar(128), created_at timestamptz, updated_at timestamptz,
  PRIMARY KEY (tenant_id, payment_id));

CREATE TABLE payment_status_transitions (      -- immutable, one row per transition of ANY status dimension
  tenant_id uuid, payment_id uuid, seq int, dimension text, from_value text, to_value text,
  cause text, interaction_id uuid, ledger_transaction_id uuid, hold_id uuid, occurred_at timestamptz,
  PRIMARY KEY (tenant_id, payment_id, seq));

CREATE TABLE provider_interactions (            -- one row per attempt; PARTITION BY RANGE (started_at)
  tenant_id uuid, interaction_id uuid, payment_id uuid, provider text, operation text,
  direction text CHECK (direction IN ('OUTBOUND','INBOUND_CALLBACK')),
  http_method text, endpoint_template text,      -- path template, no query string, no ids of other customers
  rpc_method text, attempt_no int, retry_reason text,
  correlation_id varchar(128), trace_id char(32), external_ref varchar(255), idempotency_key varchar(255),
  request_meta jsonb, response_meta jsonb,       -- allow-listed headers only
  request_payload_ref text, request_sha256 bytea, response_payload_ref text, response_sha256 bytea,
  http_status int, provider_code text, provider_message_code text,
  transport_outcome text CHECK (transport_outcome IN
     ('OK','HTTP_ERROR','TIMEOUT_AFTER_SEND','CONNECT_FAILED','TLS_FAILED','CANCELLED')),
  business_outcome  text CHECK (business_outcome IN ('ACCEPTED','REJECTED','PENDING','UNKNOWN')),
  error_class text, error_detail text,           -- sanitized
  started_at timestamptz, ended_at timestamptz, duration_ms int,
  ledger_transaction_id uuid,
  PRIMARY KEY (tenant_id, interaction_id, started_at));

CREATE TABLE payload_access_log (               -- every view of a raw payload is audited
  accessed_at timestamptz, operator_id text, role text, interaction_id uuid, purpose text, ticket_ref text);
```

قواعد:

- **[توجیه فنی] دوام**: ردیف interaction **پیش از** ارسال با `business_outcome='UNKNOWN'` در DB
  Payments درج می‌شود و پس از پاسخ تکمیل می‌شود. اگر فرایند بین این دو بمیرد، ردیف UNKNOWN برای
  inquiry باقی می‌ماند. برخلاف لاگ HTTP در Sarv، هیچ ردیفی دور ریخته نمی‌شود. اگر نوشتن این ردیف ممکن
  نباشد، فراخوانی provider انجام **نمی‌شود**.
- **[توجیه فنی] ابهام**: `TIMEOUT_AFTER_SEND` یا قطع ارتباط پس از ارسال به `business_outcome='UNKNOWN'`
  و `outcome_certainty='UNKNOWN'` منجر می‌شود. پرداخت **هرگز خودکار FAILED نمی‌شود** و hold آزاد
  **نمی‌شود**. ادامهٔ کار با inquiry یا reconciliation است (همان منطق `NeedsReconciliation` در Sarv،
  ولی صریح). شکست قطعی فقط وقتی ثبت می‌شود که خطا پیش از ارسال باشد (`CONNECT_FAILED`) یا provider
  رد کسب‌وکاری صریح بدهد.
- **Payload خام**: پیش از ذخیره redact می‌شود (credential، رمز، PIN، CVV، PAN کامل، OTP، توکن‌ها، با
  allow-list و نه فقط deny-list). داده‌ای که ذخیره‌اش ممنوع است، مثل CVV و PIN block، هرگز ذخیره نمی‌شود،
  حتی رمزگذاری‌شده. payloadهای مجاز در object storage رمزگذاری‌شده نگه داشته می‌شوند (کلید از KMS یا
  Vault). در DB فقط ارجاع و SHA-256 می‌ماند تا تغییرناپذیری قابل اثبات باشد. دسترسی برای نقش
  `payload-inspector` است و هر مشاهده در `payload_access_log` ثبت می‌شود. نگهداری باز (§20.1، مورد 6) است
  و الزام قانونی و DPO باید تعیین شود.
- **فیلترها** و ایندکس‌های read model: `payment_id`، `external_ref`، `customer_ref`، `tenant_id`،
  `provider`، هر بُعد وضعیت، `operation`، بازهٔ زمانی (BRIN یا partition)، `(asset_code, amount)`،
  `provider_code` و `http_status`، `correlation_id`، `ledger_transaction_id`، `idempotency_key`. همه با
  پیمایش keyset.
- **Timeline یک پرداخت** = ادغام زمانی این منابع: `payment_status_transitions`، `provider_interactions`،
  `provider callbacks`، و رویدادهای Ledger (`HoldCreated/Captured/Released`، `TransactionPosted/Reversed`،
  `SettlementCompleted`، `DiscrepancyDetected`) که `external_ref` آن‌ها برابر `payment_id` است.

---

## 11. Concurrency and Idempotency

### 11.1 مقایسه

| روش | رفتار در حساب داغ | انتقال چندحسابی | بازه‌ای که قفل نگه داشته می‌شود | شاهد |
|---|---|---|---|---|
| خوش‌بینانه با RowVersion و retry | تعارض با رشد همزمانی بیشتر می‌شود. کل تراکنش دوباره اجرا می‌شود | هر تعارض روی هر حساب، کل کار را تکرار می‌کند | کوتاه، چون تا SaveChanges قفل ندارد | Sarv: 8 تلاش با jitter. comment در `LedgerOptimisticRetry.cs` شکست اکثریت درخواست‌ها در سیاست قبلی را گزارش می‌کند |
| `SELECT … FOR UPDATE` مرتب | درخواست‌ها در صف می‌ایستند. throughput با latency زمان commit محدود است | قفل‌های مرتب، بدون deadlock ناشی از وارونگی ترتیب | از قفل تا commit | ADR 0017. اندازه‌گیری ندارد |
| UPDATE شرطی اتمیک (`SET b=b+Δ WHERE b+Δ ≥ 0 RETURNING …`) | یک round trip کمتر. همچنان سریالی | باید مرتب اجرا شود | از UPDATE تا commit | Sarv برای حساب سیستمی (`ExecuteUpdate`) |
| تقسیم حساب داغ به N زیرحساب | سریالی‌شدن را N برابر کاهش می‌دهد | — | — | ADR 0017، تصمیم 4 |

**توصیهٔ ترکیبی** [توجیه فنی، اعداد: بنچمارک]:

1. پیش‌فرض: قفل بدبینانهٔ مرتب، مطابق §8.2. قاعدهٔ موجودی در `ledger-core` می‌ماند و تکرار نمی‌شود.
2. برای حساب‌هایی که `allow_negative=true` هستند و fee یا clearing محسوب می‌شوند: بررسی موجودی ندارند،
   پس **UPDATE آن‌ها آخرین UPDATE پیش از INSERT idempotency** است، تا زمان نگهداشت قفل کمترین باشد.
   شمارهٔ `position_seq` و `balance_after` از `UPDATE … RETURNING` گرفته می‌شوند. این کار ترتیب
   مراحل 10 تا 14 را برای این حساب‌ها عوض می‌کند و اثبات مستقل لازم دارد که deadlock ایجاد نمی‌کند.
   استدلال: همچنان به همان ترتیب سراسری قفل گرفته می‌شود، فقط دیرتر.
3. اگر بنچمارک نشان دهد یک حساب داغ گلوگاه است، با تأیید مالک به N زیرحساب در Account Service تقسیم
   شود. این مدل مانده را تغییر نمی‌دهد.
4. retry در **سمت کلاینت** با همان کلید idempotency انجام می‌شود، برای `CONTENTION` و `UNAVAILABLE`، با
   full-jitter. همان درس Sarv است، ولی در کلاینت و نه داخل سرور. retry در سمت سرور فقط برای `40P01` و با
   حداکثر 2 تلاش، و باید با بنچمارک تأیید شود.
5. سناریوهای تست همروندی که باید وجود داشته باشند:
   - برداشت‌ها و واریزهای همزمان روی یک حساب
   - انتقال‌های A به B و B به A همزمان
   - reversal همزمان یک تراکنش
   - capture همزمان با expiry
   - capture همزمان با release
   - freeze یا seal همزمان با posting
   - کلید تکراری همزمان با payload یکسان و با payload متفاوت

   سناریوهای `distributed`، `hot` و `fee` در harness Sarv الگوی خوبی برای بنچمارک هستند.

### 11.2 الگوریتم Idempotency

| پرسش | پاسخ |
|---|---|
| دامنه | `(tenant_id, idempotency_key)` روی **همهٔ** عملیات نوشتن (ADR 0018). رکورد عملیات و `client_id` را نگه می‌دارد |
| hash | SHA-256 روی encoding کانونیک نسخه‌دار از فیلدهای کسب‌وکار. محاسبه در `ledger-application` انجام می‌شود. ترتیب postingها اهمیت دارد |
| کلید یکسان و payload یکسان | ACCEPTED: منبع ثبت‌شده با وضعیت فعلی و `idempotent_replay=true`. REJECTED: همان کد خطا |
| کلید یکسان و payload، عملیات یا client متفاوت | `DUPLICATE_IDEMPOTENCY_KEY_CONFLICT` (`ALREADY_EXISTS`/409) |
| درخواست رد شده | ردهای وابسته به وضعیت یا زمان **ثبت می‌شوند** (ADR 0019، ستون Recorded). ردهای شکل یا احراز هویت یا `CONTENTION` ثبت نمی‌شوند و دوباره ارزیابی می‌شوند |
| درخواست تکراری همزمان | `pg_advisory_xact_lock(key64)` درخواست‌های هم‌کلید را در **شروع** تراکنش سریالی می‌کند. درخواست دوم پس از commit اولی رکورد را می‌بیند و replay می‌شود. برخورد hash 64 بیتی فقط انتظار بی‌مورد ایجاد می‌کند و اثری بر درستی ندارد. unique PK پشتوانهٔ نهایی است |
| نگهداری | `ACCEPTED`: تا وقتی journal هست، یعنی برای همیشه. `REJECTED`: تا تعیین نگهداشت قانونی حذف نمی‌شوند (§20.1، مورد 6). حداقل باید از افق retry کلاینت به‌اضافهٔ زمان حل ابهام بیشتر باشد. [مسدود: اطلاعات] |
| کلیدهای سیستمی | worker و migration کلیدهای قطعی می‌سازند: `system:expire:{hold_id}`، `sarv:{OperationType}:{ReferenceId}` |

---

## 12. Multi-Currency, FX and Settlement

### 12.1 FX

- **[توجیه فنی]** هر asset به‌طور مستقل balance می‌شود و trigger I-4 بر اساس `asset_code` گروه‌بندی
  می‌کند. واحدهای ناهمگون هرگز با هم جمع نمی‌شوند.
- **Quote**: سرویس Treasury یا Payments، که مالک نرخ است، quote می‌دهد. Ledger آن را در `fx_quotes` به
  صورت snapshot ثبت می‌کند: نرخ، منبع، `quoted_at`، `valid_until`، `rounding_mode`، مبلغ‌ها و کارمزد.
- **اعتبارسنجی در Ledger**:
  - `now ≤ valid_until`، وگرنه `FX_QUOTE_EXPIRED`.
  - `target_amount` باید برابر نتیجهٔ گرد کردن `source_amount × rate` با scale‌های دو asset و
    `rounding_mode` باشد، وگرنه `FX_QUOTE_MISMATCH`.
  - محاسبه با `BigDecimal` و `MathContext` صریح در `core.fx` انجام می‌شود. **این با قاعدهٔ فعلی
    `CLAUDE.md` در تعارض است** (BigDecimal با scale غیرصفر ممنوع است)، پس قاعده باید روشن شود: ممنوعیت
    برای **مبلغ پول** است و نرخ مبلغ نیست (§19.2).
- **الگوی posting** (فروش USD و خرید EUR. اعداد فقط برای نمونه‌اند):

| حساب | جهت | مبلغ |
|---|---|---|
| مشتری USD | D | 10 000 USD |
| FX clearing USD | C | 10 000 USD |
| FX clearing EUR | D | 9 150 EUR |
| مشتری EUR | C | 9 150 EUR |
| FX clearing USD | D | 50 USD |
| درآمد spread یا کارمزد FX (USD) | C | 50 USD |

- **سود و زیان FX و ارزش‌گذاری** [تصمیم مالک]: سود spread در زمان معامله ثبت می‌شود، مطابق سطرهای بالا. نرخ اجرا
  (در `fx_quotes`) از نرخ ارزش‌گذاری حسابداری (در `fx_valuation_rates`، تغییرناپذیر و با `as_of`) جداست، تا
  گزارش هر تاریخ قابل بازتولید باشد. ارزیابی مجدد positionهای clearing یک `ADJUSTMENT` با `approval_ref` است که
  نرخ ارزش‌گذاری را ذکر می‌کند. ارز گزارش‌دهی هر tenant در `tenant_settings.reporting_asset_code` است و تا تصمیم
  کسب‌وکار NULL می‌ماند؛ این سند آن را حدس نمی‌زند و تا آن زمان جمع بین‌asset ارائه نمی‌شود (باز، §20.1).
- **Reversal FX** همهٔ legها را با **همان مبالغ اولیه** معکوس می‌کند، نه با نرخ روز. اختلاف نرخ فقط در
  ارزیابی مجدد ظاهر می‌شود.
- quote به‌طور پیش‌فرض یک‌بارمصرف است و انقضا دارد (`ux_ltx_fx_quote_once`، S16) [تصمیم مالک]. حالت چندبارمصرف در v1 نیست.

### 12.2 Settlement

- جریان: در شروع پرداخت به rail بیرونی، Bucket مشتری بدهکار و **clearing rail** (Account سیستمی، position مستقیم) بستانکار می‌شود. سپس
  تراکنش در `settlement_batch_items` قرار می‌گیرد. با تأیید بانک، **clearing** بدهکار و **settlement
  bank / nostro** بستانکار می‌شود (تراکنش `SETTLEMENT`) و batch به `SETTLED` می‌رود. اگر بانک رد کند،
  یک تراکنش `REVERSAL` یا `REFUND` بر اساس سیاست ثبت می‌شود و batch یا item به `FAILED` می‌رود.
- `settled_total` با مجموع آیتم‌ها مقایسه می‌شود. اختلاف یک discrepancy است و هرگز ویرایش نیست.
- شاهد نیاز: `TransactionStatus.PendingSettlement` در Sarv با توضیح «Paya T+1, Satna, crypto». Sarv این
  نیاز را با **تغییر وضعیت تراکنش** حل کرده است. این طرح آن را با تراکنش‌های جداگانه و batch حل می‌کند.

### 12.3 Reconciliation

- ورودی‌ها: `external_statement_lines`، که import تغییرناپذیر با hash فایل خام است، و داده‌های provider
  که از Payments یا Operations می‌آیند.
- خروجی: `recon_runs` و `recon_discrepancies`. **هیچ** ردیفی از journal تغییر نمی‌کند.
- حل اختلاف: `recon_resolutions`. اگر اصلاح مالی لازم باشد، یک تراکنش `ADJUSTMENT` تازه با
  `approval_ref` ثبت می‌شود. Reconciliation با نقش `ledger_readonly` اجرا می‌شود و فقط وقتی تراکنش اصلاحی
  لازم است از مسیر API و با scope جداگانه می‌نویسد.

---

## 13. REST/gRPC Contracts

### 13.1 فهرست RPCها (`ledger.v1`) و REST

**مدیریت Wallet و Bucket** (سرویس `WalletService`، متعلق به Ledger)

| RPC | REST | Scope | Idempotency key |
|---|---|---|---|
| `CreateWallet` (شروع saga؛ پاسخ در `PROVISIONING`) | `POST /v1/wallets` | `wallets:write` | الزامی |
| `GetWallet` | `GET /v1/wallets/{wallet_id}` | `wallets:read` | — |
| `ListWallets` | `GET /v1/wallets?owner_ref=&status=` | `wallets:read` | — |
| `SuspendWallet` / `ActivateWallet` | `POST /v1/wallets/{wallet_id}/suspension`، `…/activation` | `wallets:write` | الزامی |
| `FreezeWallet` / `UnfreezeWallet` | `POST /v1/wallets/{wallet_id}/freeze`، `…/unfreeze` | `wallets:freeze` (با `approval_ref`) | الزامی |
| `StartWalletClosing` | `POST /v1/wallets/{wallet_id}/closing` | `wallets:write` | الزامی |
| `CloseWallet` | `POST /v1/wallets/{wallet_id}/closure` | `wallets:write` | الزامی |
| `AddBalanceBucket` | `POST /v1/wallets/{wallet_id}/buckets` (فقط asset و `product_code`؛ شناسهٔ حساب از Account Service) | `wallets:write` | الزامی |
| `GetBalanceBucket` / `ListBalanceBuckets` | `GET /v1/buckets/{bucket_id}`، `GET /v1/wallets/{wallet_id}/buckets` | `wallets:read` | — |
| `SuspendBalanceBucket` / `ActivateBalanceBucket` / `CloseBalanceBucket` | `POST /v1/buckets/{bucket_id}/suspension`، `…/activation`، `…/closure` | `wallets:write` | الزامی |
| `GetWalletBalances` | `GET /v1/wallets/{wallet_id}/balances` (هر Bucket، با جمع برای هر asset) | `balances:read` | — |
| `GetBucketBalance` | `GET /v1/buckets/{bucket_id}/balance` (posted، reserved، available، `as_of_position_seq`) | `balances:read` | — |
| `ListBucketPostings` | `GET /v1/buckets/{bucket_id}/postings` (keyset روی `position_seq`. **بی‌فاصله**) | `postings:read` | — |
| `ListWalletHistory` | `GET /v1/wallets/{wallet_id}/postings` (keyset روی `(posted_at, posting_id)`. **بی‌فاصله نیست**. برای کامل بودن از `ListBucketPostings` استفاده شود) | `postings:read` | — |

**عملیات مالی** (سرویس `LedgerService`)

| RPC | REST | Scope | Idempotency key |
|---|---|---|---|
| `PostTransaction` | `POST /v1/transactions` | `transactions:write` | الزامی |
| `ReverseTransaction` | `POST /v1/transactions/{transaction_id}/reversal` | `transactions:write` | الزامی |
| `GetTransaction` | `GET /v1/transactions/{transaction_id}` | `transactions:read` | — |
| `ListTransactionsByExternalRef` | `GET /v1/transactions?external_ref=` | `transactions:read` | — |
| `GetAccountBalance` | `GET /v1/accounts/{external_account_id}/assets/{asset}/balance` (Σ positionها، محاسبه‌شده) | `balances:read` | — |
| `ListAccountPostings` | `GET /v1/accounts/{external_account_id}/assets/{asset}/postings` (فقط position مستقیم، بی‌فاصله) | `postings:read` | — |
| `BatchGetBalances` | `POST /v1/balances/batch-get` | `balances:read` | — |
| `CreateHold` | `POST /v1/holds` (هدف: Bucket یا Wallet با asset؛ `policy_code`) | `holds:write` | الزامی |
| `CaptureHold` / `ReleaseHold` | `POST /v1/holds/{hold_id}/captures`، `…/releases` | `holds:write` | الزامی |
| `CancelHold` | `POST /v1/holds/{hold_id}/cancellation` | `holds:write` | الزامی |
| `GetHold` / `ListHolds` | `GET /v1/holds/{hold_id}`، `GET /v1/holds?wallet_id=&bucket_id=&status=` | `holds:read` | — |
| `ExecuteFx` | `POST /v1/fx-executions` | `fx:write` | الزامی |
| Settlement | `POST /v1/settlement-batches`، `…/{id}/items`، `…/{id}/completion` | `settlement:write` | الزامی |
| Reconciliation | `/v1/reconciliation/...` (فقط REST عملیاتی) | `reconciliation:*` | الزامی برای نوشتن |
| `ApplyAccountState` (fence و seal) | — (فقط gRPC داخلی) | `accounts:fence` | با `account_version` |

**حذف می‌شوند** (فقط lifecycle Account): `CreateAccount`، `GetAccount`، `ListAccounts`، `CloseAccount`،
پیام `Account`، و scopeهای `accounts:write`، `accounts:read` و `accounts:overdraft`. **اضافه می‌شوند**:
`wallets:read`، `wallets:write`، `wallets:freeze`، `transactions:write`، `transactions:read`،
`transactions:system`، `transactions:exceptional`، `holds:write`، `holds:read`، `fx:write`، `settlement:write`،
`reconciliation:*` و `accounts:fence`.

**ویرایش‌های لازم در proto** (follow-up؛ `buf` روی این ماشین نصب نیست، پس در این بازنگری اعمال و lint نشد):

1. `account.proto` و `CreateAccount`/`GetAccount` از `ledger_service.proto` حذف شوند.
2. `money.proto`: `Money { string asset_code = 1; string amount = 2; }` به جای `int64 amount_minor` و currency.
3. `entry.proto` → `transaction.proto`: `Entry` → `Transaction`؛ `PostingInput.target` یک `oneof` با
   `bucket_id`، `wallet_primary { wallet_id, asset_code }` و `external_account_id` (فقط سیستمی)؛ `Posting` خروجی با
   `position_id`، `bucket_id`، `wallet_id`، `external_account_id`، `position_seq`، `account_version`،
   `balance_after` (string)؛ `sequence_no` و فیلد `correlation_id` حذف شوند.
4. `wallet.proto` تازه: `WalletService` با RPCهای جدول بالا؛ `WalletStatus` با `PROVISIONING`، `ACTIVE`،
   `SUSPENDED`، `FROZEN`، `CLOSING`، `CLOSED`؛ `CreateWalletRequest { owner_ref, wallet_type, repeated
   AssetRequest assets }` بدون شناسهٔ حساب.
5. `hold.proto`، `fx.proto`، `settlement.proto` تازه، و `ApplyAccountState` در یک سرویس داخلی جدا
   (`AccountFenceService`).
6. `error.proto`: کدهای §13.3 اضافه شوند؛ `ACCOUNT_ALREADY_EXISTS` deprecated شود.
7. بستهٔ `ledger.events.v1` (envelope و رویدادهای §14.1)؛ سپس `buf lint`، `buf breaking` و تولید دوبارهٔ
   OpenAPI.

### 13.2 نمونه

```http
POST /v1/transactions
Authorization: Bearer <jwt>      x-correlation-id: pay-7f3c…      Content-Type: application/json

{
  "idempotencyKey": "pay-7f3c:post:1",
  "externalRef": "payment:7f3c…",
  "postings": [
    {"target": {"bucketId": "0193a1…"},                                   "direction": "DIRECTION_DEBIT",  "money": {"assetCode": "IRR", "amount": "1250000"}},
    {"target": {"walletPrimary": {"walletId": "0193b7…", "assetCode": "IRR"}}, "direction": "DIRECTION_CREDIT", "money": {"assetCode": "IRR", "amount": "1240000"}},
    {"target": {"externalAccountId": "sys_fee_irr_03"},                   "direction": "DIRECTION_CREDIT", "money": {"assetCode": "IRR", "amount": "10000"}}
  ]
}
```

پاسخ برای هر posting این فیلدها را دارد: `positionId`، `bucketId`، `walletId`، `externalAccountId`،
`positionSeq`، `accountVersion`. فیلد `target` یک `oneof` است با گزینه‌های `bucket_id`، `wallet_primary`
و `external_account_id`.

```http
POST /v1/wallets
{ "idempotencyKey": "onb-552:wallet", "ownerRef": "cust_552", "walletType": "DEFAULT",
  "assets": [ {"assetCode": "IRR", "productCode": "MAIN"}, {"assetCode": "USDT", "productCode": "MAIN"} ] }
→ 200 { "walletId": "0193c4…", "status": "WALLET_STATUS_PROVISIONING" }   // شناسهٔ حساب‌ها را Account Service می‌دهد
```

خطا (REST): بدنه `google.rpc.Status` همراه با `LedgerErrorDetail` است، با HTTP 400 برای
`FAILED_PRECONDITION`:

```json
{"code": 9, "message": "insufficient available funds",
 "details": [{"@type": "type.googleapis.com/ledger.v1.LedgerErrorDetail",
              "code": "ERROR_CODE_INSUFFICIENT_FUNDS", "correlationId": "pay-7f3c…", "requestId": "r-…"}]}
```

### 13.3 کدهای خطا

جدول ADR 0019 حفظ می‌شود. `ACCOUNT_ALREADY_EXISTS` (19) deprecated می‌شود. `ACCOUNT_BALANCE_NOT_ZERO`
(21) **می‌ماند** و برای seal Account به کار می‌رود. کدهای جدید:

| کد | gRPC / HTTP | Recorded |
|---|---|---|
| `ACCOUNT_UNKNOWN` | NOT_FOUND / 404 | yes |
| `ACCOUNT_RESTRICTED` (freeze، یا debit/credit غیرمجاز) | FAILED_PRECONDITION / 400 | yes |
| `ACCOUNT_HAS_RESTRICTIONS` (seal) | FAILED_PRECONDITION / 400 | n/a (fence) |
| `ACCOUNT_STATE_STALE` | FAILED_PRECONDITION / 400 | n/a |
| `ASSET_NOT_ALLOWED` | FAILED_PRECONDITION / 400 | yes |
| `WALLET_NOT_FOUND`، `BUCKET_NOT_FOUND` | NOT_FOUND / 404 | yes |
| `WALLET_LIMIT_EXCEEDED` (سیاست کسب‌وکار) | FAILED_PRECONDITION / 400 | yes |
| `WALLET_NOT_ACTIVE`، `BUCKET_NOT_ACTIVE` | FAILED_PRECONDITION / 400 | yes |
| `WALLET_BALANCE_NOT_ZERO`، `BUCKET_BALANCE_NOT_ZERO` (شامل hold فعال) | FAILED_PRECONDITION / 400 | yes |
| `PRIMARY_BUCKET_EXISTS`، `PRIMARY_BUCKET_NOT_CLOSABLE` | FAILED_PRECONDITION / 400 | yes |
| `HOLD_NOT_FOUND` | NOT_FOUND / 404 | yes |
| `HOLD_NOT_ACTIVE`، `HOLD_EXPIRED`، `HOLD_AMOUNT_EXCEEDED` | FAILED_PRECONDITION / 400 | yes |
| `FX_QUOTE_EXPIRED` | FAILED_PRECONDITION / 400 | yes |
| `FX_QUOTE_MISMATCH` | INVALID_ARGUMENT / 400 | no |
| `SETTLEMENT_BATCH_STATE_INVALID` | FAILED_PRECONDITION / 400 | yes |
| `WALLET_FROZEN` | FAILED_PRECONDITION / 400 | yes |
| `ACCOUNT_ALREADY_BOUND` (provisioning) | FAILED_PRECONDITION / 400 | n/a (saga) |
| `APPROVAL_REQUIRED` | PERMISSION_DENIED / 403 | no |
| `FX_QUOTE_USED` | FAILED_PRECONDITION / 400 | yes |
| `HOLD_POLICY_NOT_FOUND`، `HOLD_TTL_EXCEEDED` | FAILED_PRECONDITION / 400 | yes |

یادداشت (گذر هم‌راستاسازی 2026-11-15): جدول ADR 0019 به نام‌های زیر هم‌راستا شد: `ACCOUNT_NOT_FOUND` به `ACCOUNT_UNKNOWN`؛ `ENTRY_*` به `TRANSACTION_*` (`TRANSACTION_NOT_FOUND`، `TRANSACTION_ALREADY_REVERSED`، `TRANSACTION_NOT_REVERSIBLE`)؛ `CURRENCY_MISMATCH` به `ASSET_MISMATCH`؛ و `ACCOUNT_CLOSED` و `ACCOUNT_FROZEN` بازنشسته شدند و به‌جای آن‌ها `ACCOUNT_RESTRICTED` (حساب بسته، فریز شده یا فاقد قابلیت) و `WALLET_FROZEN` (فریز Wallet یا Bucket) آمده است. پروتوهای پیش‌نویس هنوز نام‌های قدیمی را دارند و هنگام بازنویسی تغییر می‌کنند.

### 13.4 احراز هویت، timeout، صفحه‌بندی و نسخه‌بندی

- **احراز هویت و مجوز**: mTLS به‌اضافهٔ JWT client-credentials (ADR 0020). tenant از توکن خوانده
  می‌شود. scopeها همان فهرست بالا هستند. `accounts:fence` فقط به هویت Account Service داده می‌شود. `transactions:system` فقط به سرویس‌های نام‌برده و `transactions:exceptional` فقط همراه `approval_ref` داده می‌شود (ADR 0021).
- **Timeout**: deadline در gRPC از کلاینت به سرور منتشر می‌شود. سرور
  `min(deadline − margin, server_max)` را به `transaction_timeout` و `statement_timeout` تبدیل می‌کند.
  از نسخهٔ 17، PostgreSQL پارامتر `transaction_timeout` را دارد. این گزاره در این بازبینی تأیید نشد و در
  spike بررسی شود. **Retry** فقط با همان کلید، فقط برای `ABORTED`، `UNAVAILABLE`، `DEADLINE_EXCEEDED` و
  `RESOURCE_EXHAUSTED`، و با backoff.
- **صفحه‌بندی**: همه از نوع keyset هستند. `ListBucketPostings` و `ListAccountPostings` بر اساس `position_seq` هستند (ADR 0017). `ListWalletHistory` بر اساس `(posted_at, posting_id)` است و بی‌فاصله نیست.
  بقیه بر اساس `(created_at, id)` با UUIDv7. اندازهٔ صفحه 1 تا 1000 است. page token opaque است و امضا یا
  اعتبارسنجی می‌شود.
- **نسخه‌بندی**: تا `v1.0.0` قرارداد پیش‌نویس است (ADR 0019). رویدادها در بستهٔ جداگانهٔ
  `ledger.events.v1` هستند.

---

## 14. Kafka and CQRS

### 14.1 رویدادها

| Topic | کلید پیام | رویدادها | ترتیب |
|---|---|---|---|
| `ledger.transactions.v1` | `{tenant_id}:{transaction_id}` (TransactionReversed با کلید تراکنش **اصلی**) | `TransactionPosted` (sequence 1)، `FxExecuted` (sequence 2، فقط FX)، `TransactionReversed` (sequence بعدی: 2، یا 3 برای اصل FX) | هر تراکنش یک aggregate است؛ ترتیب سراسری **ندارد** (ADR 0017، ADR 0024 D1) |
| `ledger.wallets.v1` | Wallet: `{tenant_id}:{wallet_id}`؛ Bucket: `{tenant_id}:{bucket_id}` | Wallet: `WalletProvisioningStarted`، `WalletActivated`، `WalletProvisioningFailed`، `WalletStatusChanged`، `WalletClosed`؛ Bucket: `BucketCreated`، `BucketStatusChanged` | برای هر Wallet و هر Bucket مرتب است (`wallet_status_events` و `bucket_status_events`، `event_seq`) |
| `ledger.position-activity.v1` | — | **در v1 نیست** (ADR 0024)؛ `TransactionPosted` شامل `position_seq` هر posting است و افزودن بعدی additive است | — |
| `ledger.holds.v1` | `{tenant_id}:{hold_id}` | `HoldCreated`، `HoldCaptured`، `HoldReleased`، `HoldExpired` | برای هر hold مرتب است (`hold_events.event_seq`) |
| `ledger.settlement.v1` | `{tenant_id}:{batch_id}` | `SettlementOpened` (sequence 1)، `SettlementBatchSubmitted`، `SettlementCompleted`، `SettlementFailed` | برای هر batch مرتب است (`settlement_batch_events.event_seq`؛ ردیف OPEN هنگام ساخت batch نوشته می‌شود) |
| `ledger.reconciliation.v1` | `{tenant_id}:{run_id}` | `ReconciliationCompleted`، `DiscrepancyDetected` | برای هر run مرتب است (`recon_runs.event_seq`، ستون جدید) |
| مصرف می‌شود: `accounts.lifecycle.v1` (مالک: Account Service) | `{tenant}:{account_id}` | `AccountStateChanged` | برای هر حساب مرتب است. `account_version` هم جلوی کهنگی را می‌گیرد |

**Envelope** (protobuf، پیام واحد `EventEnvelope` در `contracts/proto/ledger/events/v1`، ADR 0024 D2): ذخیره‌شده در `outbox_events.payload` و ارسال‌شده به‌عنوان مقدار رکورد Kafka؛ کلید رکورد `message_key` است. فیلدهای الزامی: `event_id` (UUID)، `event_type`، `schema_version`، `tenant_id`، `aggregate_type` (TRANSACTION، WALLET، BUCKET، HOLD، SETTLEMENT_BATCH، RECONCILIATION_RUN)، `aggregate_id`، `aggregate_sequence` (≥ 1)، `occurred_at` (UTC با دقت میکروثانیه، ساعت تراکنش تولیدکننده نه زمان انتشار)، `correlation_id` (هرگز خالی نیست)، `producer` (enum بسته `Producer`، v1 فقط `PRODUCER_LEDGER`) و `payload` (bytes؛ محتوای کسب‌وکار هنوز تعریف نشده است). فیلدهای اختیاری: `causation_id` (`event_id` رویداد علت) و `trace_parent` (W3C، بدون معنای کسب‌وکار). **PII، secret، مبلغ و وضعیت در envelope ممنوع است.** در v1 رجیستری schema نیست؛ protoهای `contracts/` با `buf breaking` مرجع‌اند (ADR 0024).

### 14.2 تضمین‌ها

- **[توجیه فنی]** هیچ ادعای exactly-once بین PostgreSQL و Kafka وجود ندارد. outbox در همان تراکنش نوشته
  می‌شود. relay **حداقل یک بار** منتشر می‌کند. تنظیمات producer: `acks=all` و
  `enable.idempotence=true`. idempotence در producer فقط تکرار درون یک session را حذف می‌کند و به
  outbox ربطی ندارد. relay پس از crash ممکن است پیام را دوباره بفرستد.
- **ترتیب بر اساس aggregate (ADR 0024 D1، I-40)**: کلید ترتیب، کلید پیام Kafka است، یعنی `outbox_events.message_key` = `{tenant_id}:{aggregate_id}` (CHECK در DB این را اعمال می‌کند). ترتیب فقط برای هر aggregate تضمین می‌شود، هرگز سراسری نیست. aggregateها: transaction (TransactionPosted، FxExecuted و TransactionReversed با کلید تراکنش اصلی)، wallet (`wallet_status_events.event_seq`)، bucket (`bucket_status_events.event_seq`؛ BucketCreated و BucketStatusChanged با `bucket_id` کلید می‌شوند، نه با wallet)، hold (`hold_events.event_seq`)، batch تسویه (`settlement_batch_events.event_seq`) و run مغایرت‌یابی (`recon_runs.event_seq`، ستون جدید). TransactionPosted با کلید wallet نمی‌شود: یک تراکنش چند position و wallet را لمس می‌کند و postingها قفل wallet نمی‌گیرند، پس کلید wallet سریالی نیست. ردیف منتشرنشده فقط وقتی فرستاده می‌شود که ردیف منتشرنشدهٔ دیگری با همان کلید و `outbox_id` کوچک‌تر نداشته باشد. رویدادهای کلیدهای مختلف هرگز برای هم صبر نمی‌کنند و `outbox_id` بین کلیدها ترتیبی وعده نمی‌دهد. relay **واحد و فعال** است (leader با advisory lock) و در هر poll فقط **سرِ هر کلید** را می‌خواند. ردیف قرنطینه‌شده (I-42) تنها استثناست و relay آن را رد می‌کند.
- **شماره‌گذاری aggregate (ADR 0024 D2، I-41)**: هر رویداد Ledger ستون‌های `aggregate_type`، `aggregate_id` و `aggregate_sequence` (≥ 1) را دارد که در outbox یکتاست. منبع: برای Wallet، Bucket، Hold و Settlement batch همان `event_seq` ردیف تاریخچه‌ای که رویداد گزارش می‌دهد (batch با `SettlementOpened` و sequence 1 شروع می‌شود)؛ برای run مغایرت‌یابی `recon_runs.event_seq`؛ برای Transaction یک دنبالهٔ قطعی: `TransactionPosted` = 1، `FxExecuted` = 2 (فقط FX)، `TransactionReversed` = بعدی (2، یا 3 برای اصل FX). Transaction شمارندهٔ ذخیره‌شده ندارد، چون `ledger_transactions` append-only است و نویسندهٔ همروند ندارد؛ کلید یکتا و `ux_ltx_single_reversal` تکرار را رد می‌کنند. مصرف‌کننده آخرین sequence هر aggregate را نگه می‌دارد: مساوی یا کمتر یعنی نادیده گرفتن؛ آخرین+۱ یعنی اعمال؛ بیشتر یعنی نگه‌داشتن برای مدت محدود و سپس resync از read API. شکاف فقط پس از قرنطینه، restore یا انقضای بافر دیده می‌شود. محدودیت: اگر آخرین رویداد یک تراکنش گم شود، این جریان به‌تنهایی آن را تشخیص نمی‌دهد. پوشش آن از `TransactionPosted` خود برگشت (نوع REVERSAL و `position_seq` بی‌فاصله) و `reversed_by_transaction_id` در خواندن‌ها است. پیام‌های read باید `event_sequence` برگردانند (follow-up قرارداد).
- **داده‌های عملیاتی (ADR 0024 D7.12، I-43)**: `reason_code` از مجموعهٔ کنترل‌شده است (`UNSENDABLE_PAYLOAD`، `RECORD_TOO_LARGE`، `TOPIC_CONFIGURATION`، `AUTHORIZATION`، `OTHER`). `reason_text` اختیاری، ASCII چاپ‌پذیر، ۱ تا ۳۰۰ نویسه است و هرگز payload رویداد، داده مالی، شناسهٔ مشتری، secret یا متن خام exception، stack trace و SQL ندارد؛ رویداد با `outbox_id` ارجاع داده می‌شود. `last_error` (در outbox و در کپی قرنطینه) فقط نام کلاس خطا است (`[A-Za-z0-9_.:-]{1,200}`). `approval_ref` و `operator_id` شناسه‌اند (ASCII چاپ‌پذیر، بدون فاصله، حداکثر ۱۲۸ نویسه). DB شکل را با CHECK اعمال می‌کند؛ محتوا با روش اجرایی کنترل می‌شود (تأییدکننده و اپراتور، و بازبینی grant). هشدارها و لاگ‌ها `reason_text` را نمی‌برند، فقط `outbox_id`، `reason_code` و `approval_ref`. خواندن رکورد فقط برای عملیات و compliance از طریق `ledger_readonly`.

- **ثبات envelope (ADR 0024 D2، I-44)**: envelope یک‌بار هنگام درج ثبت می‌شود و هرگز تغییر نمی‌کند. ارسال مجدد، بازپخش و قرنطینه همان بایت‌ها را می‌فرستند. فقط `published_at`، `publish_attempts`، `last_error` و `first_failed_at` تغییر می‌کنند و فقط relay آن‌ها را تغییر می‌دهد. `ledger_app` فقط INSERT دارد. تغییر معنا نیاز به `schema_version` یا نوع رویداد جدید دارد؛ پس از v1.0.0 فقط افزودن اختیاری مجاز است.
- **نویسندهٔ رویدادهای Transaction (ADR 0024 D2.8، I-41)**: فقط مسیر نوشتن Ledger رویدادهای Transaction را می‌سازد، در همان تراکنش DB که ردیف journal را می‌نویسد: PostTransaction و use caseهای مبتنی بر آن `TransactionPosted` و `FxExecuted` را، و ReverseTransaction رویداد خود برگشت و `TransactionReversed` اصل را. relay، worker، مصرف‌کننده یا client این رویدادها را نمی‌نویسد و فقط `ledger_app` ردیف outbox درج می‌کند. ReverseTransaction positionهای اصل را به ترتیب سراسری قفل می‌کند و «قبلاً برگشت خورده» را زیر همان قفل‌ها بررسی می‌کند، پس برگشت‌های همزمان سریالی می‌شوند؛ نوع immutable تراکنش اصل تعیین می‌کند sequence 2 یا 3. **حفاظت نهایی DB**: ایندکس یکتای جزئی `ux_ltx_single_reversal` (دائمی، یک برگشت برای هر اصل) و CHECKهای `ledger_transactions` که REVERSAL را به `reverses_transaction_id` وصل می‌کنند. کلید یکتای outbox، یعنی `(tenant, aggregate_type, aggregate_id, aggregate_sequence)`، فقط تا وقتی ردیف‌های outbox هستند محافظت می‌کند، چون cleanup ردیف‌های منتشرشده را پس از `outbox_retention` حذف می‌کند؛ پس حفاظت دائمی نیست. DB بررسی نمی‌کند که عدد 2 یا 3 باشد؛ property test و تست بازسازی آن را پوشش می‌دهند. رویداد جدید event store نیست. مقادیر sequence فقط با قواعد `ledger-core` (تابع خالص بر اساس نوع تراکنش و نوع رویداد، ADR 0024 D2.8، I-41) و property test اعمال می‌شوند؛ محدودیت‌های DB (کلیدهای یکتا و `ux_ltx_single_reversal`) تکرار را رد می‌کنند، اما مقدار 2 یا 3 را بررسی نمی‌کنند.
- **مالکیت بازهٔ نگهداشت (ADR 0024 D7.7)**: مقادیر ۷/۱۴/۳۰ روز پیشنهاد است و با پذیرش ADR توسط مالک تأیید می‌شود. هر تغییر نگهداشت نیاز به «تأیید معماری» دارد: تأیید مالک مجموعهٔ ADR یا کسی که مالک تعیین می‌کند، که زنجیرهٔ `Kafka retention ≤ Outbox retention ≤ Inbox retention` را تأیید می‌کند؛ نهاد دیگری فرض نشده است. تغییر Kafka retention علاوه بر آن به تأیید Platform/عملیات Kafka نیاز دارد؛ تغییر outbox retention به مشورت با عملیات (ذخیره‌سازی) و compliance/گزارش‌دهی نیاز دارد؛ inbox retention هر مصرف‌کننده توسط تیمی تنظیم می‌شود که آن را اجرا می‌کند و هرگز کمتر از outbox retention به‌اضافهٔ بیشترین lag آن مصرف‌کننده نیست. تغییرات در مخزن پیکربندی یا runbook ثبت می‌شوند؛ ADR جدید فقط وقتی لازم است که خود قاعدهٔ ترتیب عوض شود.

- **مصرف‌کننده**: inbox با `(consumer_name, message_id)` در همان تراکنشی که اثر را می‌نویسد. offset پس از
  commit ذخیره می‌شود. وقتی فاصله‌ای در `position_seq` دیده شود، مصرف‌کننده از `ListBucketPostings` یا `ListAccountPostings`
  دوباره همگام می‌شود (resync). برای بازپخش (replay)، offset عقب برده می‌شود و inbox تکرارها را حذف
  می‌کند.
- **تنظیمات Kafka** (پیشنهاد ADR 0024): replication factor 3، `min.insync.replicas=2`، retention دست‌کم 7 روز، inbox بلندتر از outbox (ADR 0024 D1)، و برای هر مصرف‌کننده یک DLQ با هشدار. مقادیر در طراحی DR تأیید می‌شوند.
- **سه بافر نگهداشت (ADR 0024 D1، پیشنهاد)**: (۱) Kafka topic، دست‌کم ۷ روز، مسئول: Platform و عملیات Kafka، بافر انتقال برای جبران و بازپخش offset؛ (۲) outbox منتشرشده، ۱۴ روز، مسئول: تیم Ledger، بازپخش از سوی تحویل پس از خروج topic از retention؛ (۳) inbox مصرف‌کننده، ۳۰ روز، مسئول: هر تیم مصرف‌کننده (و Ledger برای inbox خودش از رویدادهای `AccountStateChanged`، ADR 0022 D2)، حافظهٔ de-duplication. قاعده: `Kafka retention ≤ Outbox retention ≤ Inbox retention`، یعنی `7 ≤ 14 ≤ 30` روز (پیشنهاد)، و inbox باید از outbox حداقل به‌اندازهٔ بیشترین lag مصرف‌کننده بلندتر باشد (استنتاج). inbox بلندترین است، چون تکرار از همهٔ مرحله‌های قبل می‌آید: تکرار پس از ack گم‌شده در producer، بازپخش outbox تا `outbox_retention`، reset offset توسط اپراتور تا retention topic، و downtime مصرف‌کننده. حافظهٔ dedupe باید از طولانی‌ترین نسخهٔ قابل ارسال مجدد بیشتر عمر کند. حذف زودهنگام ردیف inbox یعنی یک تکرار دیرهنگام می‌تواند اثر جانبی را دوباره اجرا کند، که ممکن است پول را جابه‌جا کند؛ ردیف اضافه فقط حجم است. inbox تنها دفاعی است که برای هر مرحله‌ای که تکرار را فرستاده کار می‌کند.
- **انتشار و علامت‌گذاری (ADR 0024 D7)**: relay پس از ack برکر (`acks=all`) ردیف را منتشرشده علامت می‌زند، آن هم فقط یک‌بار و با `published_at`. پس از ثبت، `published_at` قابل تغییر نیست (trigger). relay هرگز حذف نمی‌کند.
- **شکست انتشار و قرنطینه (ADR 0024 D3.2، D7.3a، D7.12)**: یک رویداد که انتشارش شکست می‌خورد، **فقط کلید خودش** را مسدود می‌کند. ردیف منتشرنشده می‌ماند، `publish_attempts` بالا می‌رود، `first_failed_at` اولین شکست را ثبت می‌کند، `last_error` فقط یک کلاس کوتاه خطا است (بدون payload و داده شخصی)، و تلاش با backoff محدودشده ادامه دارد. هیچ ردیفی پرش، جابه‌جایی، پارک یا حذف خودکار نمی‌شود و هیچ نقشی رویداد منتشرنشده را حذف نمی‌کند. کلید دائماً شکست‌خورده مسدود می‌ماند؛ رویدادهای بعدیِ همان کلید جمع می‌شوند و کلیدهای دیگر جریان دارند. این یک حادثهٔ severity-1 برای آن entity است. متریک‌ها: `blocked_keys` و سن قدیمی‌ترین سر مسدودشده؛ لاگ فقط hash کلید را دارد، نه payload. تنها راه کنار گذاشتن یک رویداد **قرنطینه** است: یک رکورد append-only در `outbox_quarantine` با `approval_ref`، `operator_id`، `reason_code`، `reason_text` (بدون payload و PII) و `blocked_at`، که فقط نقش break-glass `ledger_repair` برای یک ردیف منتشرنشده درج می‌کند. ردیف در outbox می‌ماند و `published_at` آن NULL است؛ relay آن را رد می‌کند؛ مصرف‌کننده‌ها شکاف را می‌بینند و resync می‌کنند. هر درج هشدار می‌دهد. وضعیت «dead» تصمیم‌شده نیست و لازم نیست؛ وضعیت‌ها PENDING، PUBLISHED و QUARANTINED هستند که از جدول‌ها مشتق می‌شوند (I-42).


- **ack موفق، علامت‌گذاری ناموفق**: ردیف منتشرنشده می‌ماند و با همان `event_id` دوباره فرستاده می‌شود (حداقل یک بار). مصرف‌کننده با inbox تکرار را حذف می‌کند.
- **پاکسازی (ADR 0024 D7)**: job جدا با نقش `ledger_cleanup` ردیف‌های منتشرشدهٔ قدیمی‌تر از `outbox_retention` و ردیف‌های inbox قدیمی‌تر از `inbox_retention` را در دسته‌های کوچک حذف می‌کند. حذف ناموفق در اجرای بعدی دوباره امتحان می‌شود. اگر قدیمی‌ترین ردیف منتشرشده از ۲ برابر `outbox_retention` قدیمی‌تر باشد، هشدار داده می‌شود. ردیف منتشرنشده هرگز حذف نمی‌شود.
- **بازپخش (ADR 0024 D7)**: (الف) crash یا تغییر leader: ردیف‌های منتشرنشده دوباره فرستاده می‌شوند. (ب) داخل retention topic: offset مصرف‌کننده عقب برده می‌شود و inbox تکرارها را حذف می‌کند. (ج) بعد از retention topic و داخل `outbox_retention`: runbook اپراتور بازه را با `event_id`، payload و headers اصلی و `approval_ref` دوباره منتشر می‌کند و `published_at` تغییر نمی‌کند (ابزارش هنوز ساخته نشده). (د) بعد از پایان `outbox_retention`: بازسازی از list APIs و `position_seq` بدون فاصله فقط بازیابی عملیاتی و بازسازی گزارش است، نه جای سابقهٔ حسابرسی. این بازسازی وضعیت فعلی و مشتق‌شده را می‌دهد، نه اینکه چه چیزی، چه زمانی، چند بار و با کدام نسخهٔ schema منتشر شده؛ رویدادی را که هرگز منتشر نشده بازنمی‌سازد؛ و رویداد بازسازی‌شده شاهد انتشار گذشته نیست (ADR 0024 D1، D7).
- **inbox (ADR 0024 D1، D7)**: کلید `(consumer_name, message_id = event_id)`، نگهداری بیشتر از `outbox_retention` (پیشنهاد ۳۰ روز)، و پاکسازی فقط با `ledger_cleanup`. پیش از این قاعده هیچ نقشی حذف inbox را نداشت (`ledger_app` فقط SELECT و INSERT دارد).

### 14.3 CQRS

- نوشتن از port تراکنش (ADR 0026) می‌گذرد. خواندن از read port بدون قفل.
- `GetBalance` از `balance_projections` با یک PK lookup خوانده می‌شود. این خواندن snapshot یک statement
  است و در READ COMMITTED سازگار است.
- استفاده از read replica برای گزارش‌ها [بنچمارک] است. اگر استفاده شود، داده‌ها ممکن است کهنه باشند و
  API باید `as_of_position_seq` را برگرداند.

---

## 15. Security and Database Privileges

| نقش | مجوزها | استفاده |
|---|---|---|
| `ledger_owner` | مالک schema. NOLOGIN | — |
| `ledger_migrator` | عضو `ledger_owner`. فقط در pipeline استقرار | Flyway |
| `ledger_app` | جدول‌های append-only: `SELECT`، `INSERT`. جدول‌های state: `SELECT`، `INSERT`، `UPDATE`. outbox: `INSERT`. **بدون DELETE و TRUNCATE روی هیچ جدول**. RLS اعمال می‌شود (FORCE) | API و workerها |
| `ledger_relay` | outbox: `SELECT`، `UPDATE(published_at, publish_attempts, last_error, first_failed_at)`؛ `SELECT` روی `outbox_quarantine` برای رد کردن ردیف‌های قرنطینه‌شده. **بدون DELETE و بدون INSERT در قرنطینه** | relay، مسیر انتشار (ADR 0024 D7) |
| `ledger_cleanup` | outbox و inbox: `SELECT`، `DELETE`. فقط ردیف‌های منتشرشدهٔ قدیمی‌تر از retention و inbox قدیمی؛ trigger ردیف منتشرنشده را رد می‌کند | job پاکسازی زمان‌بندی‌شده (ADR 0024 D7) |
| `ledger_readonly` | `SELECT` روی همه. یک policy با `USING (true)` | reconciliation، audit، گزارش |
| `ledger_operator` | registry مستأجرها، `tenant_settings`، `hold_policies` | پیکربندی تأییدشده |
| `ledger_repair` | NOLOGIN. UPDATE ستونی روی positionها، INSERT در `projection_repairs`، `SELECT` روی outbox و `SELECT`، `INSERT` روی `outbox_quarantine` (قرنطینهٔ رویداد تحویل‌نشدنی با `approval_ref`). فقط برای هر حادثه؛ هرگز DELETE روی رویداد | runbook ترمیم projection (ADR 0025) و قرنطینهٔ outbox (ADR 0024 D7.12) |

- **تغییرناپذیری در سطح DB**: سه لایه دارد. ① نداشتن grant. ② trigger با `RAISE` روی UPDATE، DELETE و
  TRUNCATE که حتی مالک را متوقف می‌کند، مگر trigger عمداً غیرفعال شود. ③ audit روی DDL و ALTER و
  تغییر نقش‌ها، مثلاً با pgaudit (مجوز و نسخه unverified). superuser همهٔ این‌ها را دور می‌زند، پس
  دسترسی superuser باید از نوع break-glass و با ثبت باشد. این لایه از Oracle Immutable Tables در ADR 0007
  و old 0028 **ضعیف‌تر** است. این ضعف با تصمیم مالک برای PostgreSQL پذیرفته شده است (ADR 0014). کشف دستکاری با بررسی زنجیرهٔ `position_seq`
  و `balance_after` و I-4 در reconciliation انجام می‌شود.
- **RLS** [توجیه فنی، هزینه: بنچمارک]: در پیش‌نویس old 0027 به دلیل ابهام در edition Oracle به تعویق افتاده بود.
  در PostgreSQL محدودیت edition وجود ندارد. پس RLS لایهٔ **دوم** است و جایگزین predicate صریح نیست.
  `set_config('app.tenant_id', …, true)` در شروع هر تراکنش صدا زده می‌شود و فقط در همان تراکنش معتبر
  است، پس با connection pool امن است. اگر تنظیم نشده باشد، هیچ ردیفی برگردانده نمی‌شود (fail closed).
  تست‌ها باید نشان دهند که نبود تنظیم صفر ردیف یا خطا برمی‌گرداند.
- **Secrets** فقط از Vault یا env می‌آیند. اتصال DB با TLS است. توصیه: credential کوتاه‌عمر از Vault
  (database secrets engine). مجوز و دسترس‌پذیری Vault [بررسی انسانی] است.
- **لاگ**: بدنهٔ درخواست، `external_ref` و `external_account_id` ممکن است مرتبط با PII باشند (ADR
  0011). `external_account_id` باید opaque باشد و Account Service نباید شمارهٔ حساب واقعی یا IBAN را
  به‌عنوان شناسه بفرستد. این یک **الزام قرارداد** برای Account Service است (بخشی از قرارداد Account Service، ADR 0021).
- **checklist** (`docs/security/checklist.md`) و threat model در 2026-11-15 بازنویسی شدند.

---

## 16. Operational Requirements

### 16.1 داده‌هایی که در دسترس نیستند (باز یا اندازه‌گیری‌نشده) و آنچه باید اندازه‌گیری شود

| مورد | وضعیت | اندازه‌گیری یا تصمیم لازم |
|---|---|---|
| TPS میانگین و اوج، تعداد posting در هر تراکنش | **باز**. عددی جعل نمی‌شود | از تولید Sarv: شمار `Transactions` و `LedgerEntries` در ساعت اوج. سپس بنچمارک مستقل Kimia با سناریوهای `distributed`، `hot` و `fee` (ADR 0017) |
| نرخ برخورد روی حساب داغ (fee، clearing) | اندازه‌گیری نشده | سهم تراکنش‌هایی که حساب fee را لمس می‌کنند، و p99 زمان commit |
| حجم و رشد داده | اندازه‌گیری نشده | اندازهٔ هر posting و ایندکس در PG18 با داده‌های نمونه |
| نگهداری قانونی journal، payload و idempotency | **باز** | واحد حقوقی و DPO |
| RPO و RTO در دامنهٔ HA | [تصمیم مالک]: RPO=0 برای commitهای تأییدشده در دامنهٔ HA همگام؛ RTO=15 دقیقه به‌عنوان هدف، تأییدنشده | تمرین failover و restore؛ اثر replica همگام بر latency [بنچمارک] (ADR 0014) |
| RPO و RTO بین‌منطقه‌ای | **باز** | طراحی منطقه و lag اندازه‌گیری‌شده |
| تعداد tenant | اندازه‌گیری نشده | Sarv تک-tenant است |
| کهنگی مجاز cache حساب | [تصمیم مالک]: اولیه 30 ثانیه، قابل پیکربندی؛ SLO دقیق **باز** | lag رویداد و latency lookup |
| مقادیر timeout (ADR 0017) | از بنچمارک | پس از بنچمارک |
| TTL hold | **باز**: از policy هر محصول، بدون مقدار سراسری | پنجرهٔ inquiry provider و عمر مجوز rail |
| نیرو و on-call | **باز** | مدیریت مهندسی |

### 16.2 پشتیبان‌گیری، بازیابی و تکثیر

- WAL archiving و PITR با ابزاری مثل pgBackRest (مجوز و نسخه unverified). بازیابی آزمایشی دوره‌ای و
  اندازه‌گیری RTO واقعی.
- replication همگام در دامنهٔ HA (`synchronous_commit = on`، `synchronous_standby_names`) و failover فقط به
  standby همگام (ADR 0014). ابزار در طراحی DR انتخاب می‌شود (مجوز و نسخه unverified). پس از failover،
  کلاینت‌ها با همان کلید retry می‌کنند. اگر standby همگام در دسترس نباشد، نوشتن متوقف می‌شود و RPO بی‌صدا
  کاهش نمی‌یابد (runbook).
- Kafka: اگر DB به نقطه‌ای پیش از رویدادی که ارسال شده بازگردد، رویداد بدون پشتوانه می‌ماند. در دامنهٔ HA با
  RPO=0 این پیش نمی‌آید؛ فقط در بازیابی بین‌منطقه‌ای (که RPO آن باز است). مصرف‌کنندگان رویداد را با API
  مقایسه می‌کنند.

### 16.3 بازسازی projection و تشخیص drift

**تشخیص** (پنجره‌ای، keyset، فقط تشخیص، با نقش `ledger_readonly`؛ الگوی `RunLedgerAuditHandler` در
Sarv):

1. برای هر position در پنجرهٔ keyset، باید داشته باشیم `posted_balance = balance_after` آخرین posting
   و `last_position_seq = max(position_seq)`. این بررسی O(1) برای هر position است. نگاشت هم بررسی
   می‌شود: هر posting روی position یک Bucket باید `balance_bucket_id` و `wallet_id` درست داشته باشد
   (`MAPPING_MISMATCH`). همچنین هیچ Bucket یا Wallet بسته‌ای نباید مانده یا hold داشته باشد.
2. زنجیره (افزایشی از آخرین checkpoint): `balance_after(k) = balance_after(k−1) + s(P_k)` و شماره‌ها
   بی‌فاصله باشند.
3. `reserved_amount` برابر مجموع باقیماندهٔ holdهای ACTIVE باشد. `active_hold_count` با شمار آن‌ها برابر
   باشد.
4. هر تراکنش برای هر asset متعادل باشد (برای کشف دور زدن trigger).
5. برای هر tenant و asset، مجموع `posted_balance` برابر 0 باشد (I-12).

**بازسازی** (فقط با تأیید انسانی و از طریق runbook):

1. هیچ‌وقت خودکار نیست.
2. حساب با یک restriction عملیاتی از posting جدید مسدود می‌شود.
3. مقدار درست از journal محاسبه می‌شود.
4. **مقادیر قدیم و جدید و `approval_ref` در `projection_repairs` ثبت می‌شوند.**
5. UPDATE با نقش `ledger_repair` انجام می‌شود.
6. اگر خود journal اشتباه باشد، projection دست نمی‌خورد و یک تراکنش `ADJUSTMENT` ثبت می‌شود.

این، تصمیم «Nothing is rebuilt» در پیش‌نویس old 0029 را بازنگری می‌کند.

### 16.4 پایش و هشدار

| شاخص | هشدار |
|---|---|
| p50/p95/p99 نوشتن، و زمان انتظار برای قفل | بالاتر از هدف (هدف پس از بنچمارک، §20.1) |
| نرخ `CONTENTION` و deadlock | — |
| عمق و عمر outbox، یعنی قدیمی‌ترین ردیف منتشرنشده | — |
| lag مصرف‌کننده و lag رویداد حساب | — |
| تعداد holdهای منقضی‌شده ولی آزادنشده | — |
| drift، فاصلهٔ seq، تراکنش نامتعادل | **sev-1 فوری** |
| خطای trigger append-only | تلاش برای دستکاری: امنیتی |
| تأخیر replication و موفقیت آرشیو WAL | — |
| تعداد پرداخت‌های UNKNOWN بالاتر از آستانه | (Payments) |

### 16.5 مهاجرت روی جدول‌های بزرگ

- الگوی expand/contract. ستون جدید nullable یا با default ثابت اضافه می‌شود (بدون بازنویسی جدول).
- `CREATE INDEX CONCURRENTLY` بیرون از تراکنش اجرا می‌شود. نحوهٔ تعریف migration غیرتراکنشی در Flyway
  12.x: unverified، در spike بررسی شود.
- روی جدول‌های append-only هیچ `ALTER … TYPE` که بازنویسی کند مجاز نیست. اگر schema باید تغییر کند،
  جدول جدید به‌اضافهٔ view ساخته می‌شود (همان روح ADR 0014، تصمیم 5).
- مسئولیت on-call: مالک Ledger به drift، outbox و DB پاسخ می‌دهد. Payments به UNKNOWN و provider پاسخ
  می‌دهد. Account Service به fence و lag پاسخ می‌دهد. ساختار تیم و شیفت باز است (§20.1).

---

## 17. .NET vs Java Decision

**شواهد موجود (و فقط همین‌ها)**: مالک یک codebase کارکنندهٔ .NET 10 دارد
(`global.json` SDK 10.0.300، EF Core 10.0.5، WolverineFx 6.8.0، SQL Server) که ماژول Ledger، تست‌ها و
harness بار را شامل می‌شود. مالک یک اسکلت Java 25 و Spring Boot 4.1.2 هم دارد که مستندات گسترده دارد و
کد دامنه ندارد. ADR 0001 و 0012 و `README` نشان می‌دهند Kimia به‌عنوان **محصول متن‌باز مستقل** (Apache
2.0) تعریف شده است. هیچ شاهدی دربارهٔ اندازه، مهارت یا ترجیح تیم در مخازن **وجود ندارد**.

| معیار | A: درون monolith .NET | B: سرویس مستقل .NET | C: سرویس مستقل Java 25 |
|---|---|---|---|
| جداسازی دامنه | ضعیف‌تر، چون DB و فرایند مشترک‌اند. با schema و نقش DB جدا قابل بهبود است | قوی | قوی |
| درستی مالی | به طراحی بستگی دارد و به زبان بستگی ندارد | همان | همان |
| کنترل SQL و تراکنش | EF Core. برای `FOR UPDATE` به SQL خام نیاز هست | Npgsql با Dapper یا SQL خام | jOOQ یا JDBC |
| Kafka | Wolverine امروز broker ندارد. نیاز به transport Kafka دارد (unverified) | Confluent.Kafka (librdkafka) | client مرجع Kafka به زبان Java است. این یک واقعیت است و به معنای «بهتر» نیست |
| REST و gRPC | ASP.NET Core و Grpc.AspNetCore | همان | Spring و grpc-java (BOM: 1.84.1) |
| ریسک مهاجرت | **کمترین**. کد موجود تکامل می‌یابد | متوسط. بخشی از کد و تست‌ها قابل حمل‌اند | **بیشترین**. بازنویسی کامل |
| کار لازم در هر حالت | بیرونی کردن Account (Wallet و Bucket در Ledger می‌مانند)، یک مدل مانده، چند asset، تغییرناپذیری DB، tenant و hold جزئی **باید در هر سه گزینه بازطراحی شوند** (§3) | همان | همان |
| مالکیت بلندمدت و متن‌باز بودن | به Sarv گره خورده است | ممکن | با ADR 0001 و 0012 هم‌خوان است |
| مهارت تیم و استخدام | **[مسدود: اطلاعات] / [تأیید مالک]** | | |

**ارزیابی**: هیچ دلیل فنی برای برتری ذاتی Java یا .NET در درستی یا کارایی وجود ندارد. انتخاب C فقط
وقتی موجه است که یکی از این دو صادق باشد:

- (1) Kimia واقعاً قرار است محصول متن‌باز مستقل با مصرف‌کنندگان غیر Sarv باشد. ADR 0012 این را نشان
  می‌دهد.
- (2) تیم مسئولیت عملیات JVM را می‌پذیرد.

اگر فقط Sarv مصرف‌کننده است و تیم .NET است، **B کمترین ریسک را دارد** و A گام میانی منطقی است.
**[تصمیم مالک] (2026-11-15)**: گزینهٔ C. Ledger یک سرویس Java 25 و Spring Boot 4.x است که مستقل از monolith
.NET در Sarv مستقر می‌شود. این یک انتخاب سازمانی و معماری است و ادعا نمی‌کند Java سریع‌تر یا امن‌تر است. هزینهٔ
مهاجرت و مسئولیت عملیات JVM پذیرفته شده است (ADR 0014). جدول و ارزیابی بالا به‌عنوان دلیل مقایسه باقی
می‌مانند و تصمیم را دوباره باز نمی‌کنند.

---

## 18. Migration Strategy

اصل حاکم این است که **در هر لحظه، برای هر جریان مالی (و هر حساب) دقیقاً یک سیستم مرجع ثبت (system of
record) وجود دارد**. ثبت دوگانه (dual-write) از کد کاربر وجود نخواهد داشت.

| فاز | کار | خروجی و معیار پیشرفت |
|---|---|---|
| 0 | پذیرش ADRها، اصلاح `CLAUDE.md` و proto، spike روی PG18، jOOQ و Kafka | ADRها Accepted. spike سبز |
| 1 | Kimia در حالت **shadow**: Sarv مرجع است. رویدادهای `TransactionPosted` و `TransactionReversed` در Sarv، که امروز با Wolverine و صف محلی منتشر می‌شوند، به Kafka پل زده می‌شوند و Kimia آن‌ها را با کلید `sarv:{OperationType}:{ReferenceId}` import می‌کند | هر روز مانده‌ها در دو سیستم مقایسه می‌شوند و drift صفر است. **کلید باید OperationType را شامل شود**، چون یکتایی در Sarv روی `(ReferenceId, OperationType)` است |
| 2 | Account Service: هر `LedgerAccount` مشتری در Sarv (1:1 با یک `BalanceBucket`) به یک حساب `CUSTOMER` در Account Service نگاشت می‌شود و یک `external_account_id` با asset همان ارز Bucket می‌گیرد. `SystemAccount` و `LedgerAccount`های هر ارز آن به حساب‌های `SYSTEM` نگاشت می‌شوند. Account Service رویداد `AccountStateChanged` و `BatchGetAccounts` را عرضه می‌کند | Kimia cache و snapshot را پر می‌کند. هیچ دو Bucket مشتری یک `(حساب، asset)` ندارند |
| 2b | **Provisioning Walletها**: برای هر Wallet در Sarv، Kimia یک Wallet با همان شناسه در `PROVISIONING` می‌سازد و saga با `account_request_key = sarv:wallet:{WalletId}` اجرا می‌شود؛ Account Service حساب‌های مرحلهٔ 2 را برمی‌گرداند و حساب تازه نمی‌سازد. Bucketها با همان شناسه‌های Sarv ساخته می‌شوند. Wallet فقط پس از نگاشت همهٔ Bucketها `ACTIVE` می‌شود. `WalletStatus` و وضعیت Bucket در Sarv به وضعیت‌های Kimia نگاشت می‌شوند | همهٔ Walletهای Sarv در Kimia `ACTIVE` یا `CLOSED` هستند و saga ناتمام نمانده است |
| 3 | **مانده‌های افتتاحیه**: در cut-over برای هر position یک تراکنش `OPENING_BALANCE` با origin `SYSTEM` در مقابل یک حساب سیستمی انتقال (migration suspense) ثبت می‌شود. منبع: `BalanceBucket.TotalBalance` برای Bucketها و `LedgerAccount.BalanceMinorUnits` برای حساب‌های سیستمی. `LockedBalance` منتقل نمی‌شود (فاز 4). replay کامل تاریخچه فقط اگر audit آن را بخواهد | مجموع حساب انتقال صفر است |
| 4 | **Cut-over برای هر جریان** (مثلاً انتقال داخلی، سپس hold و پرداخت): Payments با feature flag به gRPC Kimia سوئیچ می‌کند. همان جریان در Sarv فقط خواندنی می‌شود. reservationهای فعال **منتقل نمی‌شوند**: جریان drain می‌شود یا تا انقضا صبر می‌شود | هیچ تراکنش جدیدی در Sarv برای آن جریان ثبت نمی‌شود |
| 5 | **Rollback**: تا پایان پنجرهٔ rollback، رویدادهای Kimia به‌صورت معکوس در Sarv import می‌شوند. هر دو سیستم کلید قطعی دارند، پس import تکراری بی‌اثر است. پس از بسته شدن پنجره، rollback یعنی forward-fix | runbook و تمرین rollback |
| 6 | خاموش کردن مسیر نوشتن در Ledger Sarv | — |

- **سازگاری رویدادها**: رویدادهای Sarv (`TransactionPostedIntegrationEvent`، با `decimal Amount`) با یک
  adapter به `ledger.events.v1` نگاشت می‌شوند. مبلغ decimal با `FromMajor` و scale مربوط به asset به واحد
  خرد تبدیل می‌شود و اگر کسری باقی بماند **رد می‌شود**.
- **جلوگیری از ثبت مضاعف**: در فاز 4، gate تغییر مسیر در Payments پیش از سوئیچ بررسی می‌کند که در Sarv
  هیچ reservation فعال یا تراکنش pending برای آن جریان نمانده باشد.
- **سازگاری idempotency**: کلیدهای Sarv به کلیدهای Kimia نگاشت قطعی دارند. Payments در دورهٔ گذار همان
  کلید را به هر دو مسیر می‌دهد. چون در هر لحظه فقط یکی مرجع است، این به ثبت دوگانه نمی‌انجامد.

---

## 19. ADR-by-ADR Verdict

### 19.1 حکم‌ها

یادداشت ادغام (2026-11-15): حکم‌ها برای شماره‌های پیش‌نویس قدیم (old) داده شده‌اند. ستون آخر ADR فعلی را نشان می‌دهد که آن تصمیم اکنون در آن است؛ نقشهٔ کامل در `docs/adr/README.md`.

| ADR (old) | حکم | دلیل و شاهد | پیامد | اکنون در |
|---|---|---|---|---|
| old 0014 per-account sequence | **REVISE** | ایدهٔ اصلی درست است: commit خارج از ترتیب، ترتیب سراسری را غیرممکن می‌کند. کلید از `account_id` به **`position_id`** تغییر می‌کند (Bucket، یا position مستقیم Account+asset). `as_of` برای position خالی برابر `created_at` همان Bucket یا position است | `position_seq` و `balance_after` روی posting | 0017 |
| old 0015 REST conforms to transcoding | **ACCEPT** | استدلال به پایگاه داده وابسته نیست. حذف 422 درست است | مسیرهای §13.1 | 0019 |
| old 0016 entry model | **REVISE** | `effective_at`، reversal ساخته‌شده در سرور، حساب تکراری و `PostingInput` حفظ می‌شوند. هدف posting به صورت `oneof` درمی‌آید (Bucket، Wallet اصلی، Account مستقیم). جدول پیوند reversal با partial unique index جایگزین می‌شود. `REFUND` و `ADJUSTMENT` اضافه می‌شوند. «Account بسته» شامل Wallet و Bucket بسته هم می‌شود | `Entry` به `Transaction` تغییر نام می‌دهد | 0016 |
| old 0017 idempotency semantics | **REVISE** | معنا درست است. abort تراکنش در PostgreSQL، advisory lock، نگهداری `REJECTED`، و اضافه شدن عملیات Wallet، Bucket، hold و fence به فضای کلید | §11.2 | 0018 |
| old 0018 error contract | **REVISE** | نگاشت وضعیت‌ها و header درست است. کدهای Wallet، Bucket، hold، FX، Account و asset اضافه می‌شوند | §13.3 | 0019 |
| old 0019 money range | **REVISE** | int64 به دامنهٔ asset‌ها وابسته است و [مسدود: اطلاعات] است. exponent برای هر حساب به scale تغییرناپذیر برای هر `asset_code` تغییر می‌کند. FX با snapshot quote و بررسی آن | §7.2، §12.1 | 0015 |
| old 0020 insert-only balances | **REJECT** | hold و موجودی reserved و available بدون projection قابل‌تغییر ممکن نیست. `FOR UPDATE` در PostgreSQL به UPDATE نیاز دارد. بخش‌های **حفظ‌شده**: `balance_after` روی posting، comparator قفل، journal بدون UPDATE | ADR تازه: positionها و زنجیرهٔ تغییرناپذیر | 0017 |
| old 0021 no events in v1 | **REJECT** | مصرف‌کنندگان در baseline نام برده شده‌اند. قید چهارم آن **حفظ می‌شود** | §14 | 0024 |
| old 0022 no holds in v1 | **REJECT** | hold بومی روی Bucket در baseline آمده است | §8.5 | 0023 |
| old 0023 service edge | **ACCEPT** | registry، allow-list و fail-closed درست‌اند | scopeهای `wallets:*` و `accounts:fence` | 0020 |
| old 0024 overdraft scope | **REVISE** | Ledger Account نمی‌سازد. سیاست و سقف اضافه‌برداشت از Account Service می‌آید (snapshot). اصل «منبع پول ساختن کاری با امتیاز ویژه است» به Account Service منتقل می‌شود. **قاعدهٔ سطح Wallet** (Bucketها به‌طور پیش‌فرض منفی نمی‌شوند) در Ledger می‌ماند. ریسک «هر کلاینت با `transactions:write` روی هر هدف tenant می‌نویسد» باقی است | [تأیید مالک]: حق posting برای هر Wallet، Account یا کلاس | 0020 |
| old 0025 account lifecycle and lookup | **REVISE** (تقسیم) | **در Ledger می‌ماند، ولی برای Wallet و Bucket**: ایجاد با idempotency، lookup و فهرست (`ListWallets` با `owner_ref`، که جای `ListAccounts` با `external_ref` را می‌گیرد)، بستن فقط با موجودی صفر و بدون hold، `CLOSED` پایانی، نبود `metadata` (`owner_ref` opaque است)، و ویژگی‌های هویتی تغییرناپذیر (trigger). **به Account Service منتقل می‌شود**: ایجاد Account، یکتایی `external_ref` Account، نوع، ارز، `allow_negative`، و تغییرناپذیری آن‌ها. «وضعیت به صورت insert-only روی ردیف‌های closure» با ستون وضعیت به‌اضافهٔ `*_status_events` تغییرناپذیر جایگزین می‌شود | §8.4، §13.1 | 0021 |
| old 0026 timeouts | **ACCEPT** | سیاست درست است. سازوکارها برای PostgreSQL بازنویسی شوند (`lock_timeout`، `statement_timeout`، `transaction_timeout` (unverified)، `idle_in_transaction_session_timeout`) | اعداد NOT DECIDED | 0017 |
| old 0027 tenant isolation | **REVISE** | FKهای composite حفظ می‌شوند. RLS به‌عنوان لایهٔ دوم (بدون محدودیت edition) [بنچمارک] | §7.3، بخش 14 | 0020 |
| old 0028 Oracle only | **REJECT** | baseline PostgreSQL 18 است. مشکل مجوز درایور Oracle حذف می‌شود و jOOQ OSS مجاز است. **پیش‌شرط**: مالک تأیید کند Oracle دیگر الزام نیست | Immutable Tables از دست می‌رود. جایگزین: grant، trigger و audit | 0014 |
| old 0029 reconciliation/freeze/recovery | **REVISE** | «هیچ چیز بازسازی نمی‌شود» با runbook §16.3 جایگزین می‌شود. freeze دو نوع می‌شود: **محلی** (تعلیق Wallet یا Bucket، با API و scope `wallets:write`) و **بیرونی** (Account، با fence). freeze ناشی از حادثهٔ داخلی Ledger با تعلیق Wallet یا Bucket و `reason_code` انجام می‌شود | §8.4، §9، §16 | 0025 |
| old 0030 transaction port | **ACCEPT** | متدها به‌روز می‌شوند: `lockAccountAnchors`، `lockWallet`، `lockPositions`، `lockHolds`، `appendTransaction`، `updatePosition`، `updateWalletStatus`، `appendOutbox`، `recordOutcome` | — | 0026 |
| old 0031 ledger-contracts | **ACCEPT** | `ledger.events.v1` و `WalletService` اضافه می‌شوند (2026-11-15: mapping moved to the adapters, ADR 0026 D2) | — | 0026 |
| old 0032 contract freeze | **ACCEPT** | با تغییرات بزرگ قرارداد، این ADR حیاتی‌تر از قبل است | — | 0019 |

جمع (شماره‌های old): ACCEPT 6 (0015، 0023، 0026، 0030، 0031، 0032)، REVISE 9 (0014، 0016، 0017، 0018، 0019، 0024،
0025، 0027، 0029)، REJECT 4 (0020، 0021، 0022، 0028)، DEFER 0. **تغییر نسبت به پیش‌نویس قبلی این
سند**: 0025 از REJECT به REVISE تغییر کرد. ADRهای پذیرفته‌شده‌ای که اثر می‌گیرند: 0002 باید Superseded
شود. 0007، 0008، 0010، 0011 (`owner_ref` opaque) و 0013 amend می‌شوند. **هیچ تغییری در وضعیت ADRها
اعمال نشده است.**

**به‌روزرسانی 2026-11-15.** پس از تصمیم‌های نهایی مالک، حکم‌های بالا اجرا شدند و سپس همهٔ پیش‌نویس‌های `Proposed`
در سیزده ADR ادغام شدند (همگی `Proposed`): 0014 پلتفرم، PostgreSQL 18 و jOOQ، نقش‌ها، RLS و اهداف دوام (رد Oracle و
جایگزینی 0002 با تصمیم مالک)؛ 0015 پول و asset؛ 0016 مدل journal؛ 0017 projection موجودی، مسیر نوشتن، ترتیب قفل و
timeoutها؛ 0018 idempotency؛ 0019 قرارداد API و خطا؛ 0020 هویت، tenant و scopeها؛ 0021 مالکیت، Wallet و Bucket،
saga و مرز timeline پرداخت؛ 0022 همگام‌سازی Account بیرونی و اضافه‌برداشت؛ 0023 hold؛ 0024 رویدادها؛ 0025 FX،
تسویه، reconciliation و ترمیم projection؛ 0026 ساختار ماژول‌ها. پیش‌نویس‌های رد یا جایگزین‌شده در بخش «Alternatives
rejected» همان ADR ثبت شده‌اند. وضعیت هیچ ADR به Accepted یا Superseded تغییر نکرد. جدول بالا تاریخی است.

### 19.2 تغییرات لازم در `CLAUDE.md` و قرارداد

**`CLAUDE.md`** (این تغییرات در 2026-11-15 اعمال شدند؛ ستون «متن فعلی» تاریخی است)

| متن فعلی | تغییر |
|---|---|
| «Java 25, Spring Boot, Gradle (Kotlin DSL), Oracle» | → PostgreSQL 18 |
| جدول ماژول‌ها: `ledger-persistence-oracle`، «No jOOQ (commercial with Oracle)» | → `ledger-persistence-postgres`. «jOOQ OSS (Apache-2.0) مجاز است، فقط در persistence». ماژول‌های `ledger-contracts`، `ledger-messaging-kafka` و `ledger-account-client` اضافه می‌شوند |
| «Account types: ASSET, …, each with a normal balance side» | → طبقه‌بندی از Account Service می‌آید و Ledger آن را snapshot می‌کند |
| «Money = integer minor units (`long` / `NUMBER(38,0)`)» | → `NUMERIC(38,0)` و `BigInteger` (§7.2). روی wire به صورت string |
| «Never … `BigDecimal` with scale ≠ 0» | → «for **money amounts**». نرخ FX با `BigDecimal` و `MathContext` صریح فقط در `core.fx` مجاز است |
| «Plan for Oracle Immutable / Blockchain tables» | → grant، trigger و audit (§15) |
| Scopes: `accounts:write`, `accounts:read`, `entries:*` | → فهرست §13.1 (از جمله `wallets:write` و `wallets:read`) |
| (تازه) | جدول مالکیت §1.1 اضافه شود: Wallet و BalanceBucket در Ledger، Account بیرونی. «Ledger جدول مرجع Account ندارد. Wallet و Bucket جدول‌های مرجع Ledger هستند و DELETE روی آن‌ها ممنوع است» |
| Write path: «lock accounts … update balances → insert outbox row» | به‌جز ترتیب anchor و projection و idempotency، **همین می‌ماند**. پیش‌نویس‌های old 0020 و old 0021 با آن در تعارض بودند |
| Commands: `Oracle image: gvenzl/oracle-free` | → `postgres:18` (tag دقیق و digest پین شوند) |
| Forbidden: «no jOOQ»، «SQL outside `ledger-persistence-oracle`» | → «SQL و jOOQ بیرون از `ledger-persistence-postgres` ممنوع است» |
| فهرست مجوزهای مجاز | بدون تغییر. jOOQ OSS، pgJDBC، kafka-clients و Spring Kafka در این فهرست می‌گنجند. مجوز pgJDBC در این بازبینی دوباره بررسی نشد |

**`ArchitectureTest`**: `oracle..` در قاعده‌ها با `org.postgresql..` و `org.jooq..` جایگزین شود و قاعدهٔ
Kafka اضافه شود. این **سخت‌تر کردن** قاعده است و آن را loosen نمی‌کند.

**قرارداد (`contracts/proto/ledger/v1`)**:

- `account.proto` حذف می‌شود.
- `Posting.account_id` با یک `target` از نوع `oneof` (`bucket_id`، `wallet_primary`، `external_account_id`) و `Money.asset_code` جایگزین می‌شود. `Posting` خروجی، `position_id`، `bucket_id`، `wallet_id` و `external_account_id` را دارد.
- `WalletService` (`wallet.proto`) اضافه می‌شود (§13.1).
- `amount_minor` از `int64` به `string` تغییر می‌کند (تصمیم `NUMERIC(38,0)`).
- `sequence_no` و `correlation_id` حذف می‌شوند و `position_seq`/`account_version` اضافه می‌شوند.
- `Entry` به `Transaction` تغییر نام می‌دهد.
- RPCهای hold، FX، settlement، reconciliation و fence اضافه می‌شوند.
- `ErrorCode` طبق §13.3 به‌روز می‌شود.
- بستهٔ `ledger.events.v1` اضافه می‌شود و OpenAPI دوباره تولید می‌شود.

همهٔ این‌ها پیش از `v1.0.0` مجاز است (ADR 0019).

**مستندات**: `invariants.md`، `threat-model.md` و `checklist.md` در 2026-11-15 بازنویسی شدند.

---

## 20. Open Decisions and Risks

### 20.1 موارد باز (فقط این موارد)

تصمیم‌های دیگر گرفته شده‌اند (ADRهای 0014 تا 0026). هیچ‌کدام از موارد زیر مستندات را مسدود نمی‌کند.

| # | مورد | فرض فعلی | بنچمارک یا داده لازم | مالک تصمیم |
|---|---|---|---|---|
| 1 | RPO و RTO بین‌منطقه‌ای | هیچ تعهدی؛ RPO=0 فقط در دامنهٔ HA | طراحی منطقه، lag اندازه‌گیری‌شده | کسب‌وکار و عملیات |
| 2 | اهداف عددی TPS و latency | هیچ عددی | بنچمارک‌های §20.2 با ورودی تولید Sarv | محصول و عملیات |
| 3 | مقادیر دقیق TTL hold | هیچ مقداری در کد؛ ردیف policy پیش از تولید | پنجرهٔ inquiry provider، عمر مجوز rail/کارت | مالک محصول با Payments |
| 4 | ارز عملیاتی/گزارش‌دهی هر tenant | NULL؛ جمع بین‌asset ارائه نمی‌شود | سیاست حسابداری | مالی هر tenant |
| 5 | منابع نرخ FX مجاز و سیاست ارزش‌گذاری | quote منبع خود را می‌گوید؛ نرخ ارزش‌گذاری ذخیره می‌شود ولی تا تأیید استفاده نمی‌شود | قرارداد provider | Treasury و مالی |
| 6 | دورهٔ نگهداشت قانونی و نگهداشت payload خام پرداخت | هیچ ردیف مالی حذف نمی‌شود؛ Ledger payload نگه نمی‌دارد | الزام ناظر | حقوقی و DPO |
| 7 | SLO دقیق تازگی Account Service | 30 ثانیه، قابل پیکربندی | lag رویداد و latency lookup زیر بار | مالکان Account Service و Ledger |
| 8 | نیرو و پوشش on-call | تیم Kimia سرویس JVM را اداره می‌کند | برنامهٔ نیرو پیش از تولید | مدیریت مهندسی |
| 9 | مقادیر نگهداشت و زمان‌بندی cleanup | پیشنهاد: topic دست‌کم ۷ روز ≤ `outbox_retention` ۱۴ روز < `inbox_retention` ۳۰ روز؛ زمان‌بندی تعیین نشده | حجم outbox و زمان بازپخش از راه runbook | مالک Ledger، Platform (topic) و تیم‌های مصرف‌کننده (inbox) |
| 10 | بایگانی برای الزام قانونی (payload و رویدادها) | بایگانی عملیاتی لازم نیست در v1؛ outbox بایگانی قانونی نیست و پاکسازی آن را برآورده نمی‌کند. مالکیت بایگانی قانونی با compliance و گزارش‌دهی (همراه حقوقی و DPO) | الزام ناظر؛ تست بازسازی از جدول‌ها (استنتاج: قابل بازسازی، ولی بازسازی سابقهٔ حسابرسی نیست) | compliance و گزارش‌دهی، حقوقی و DPO |
| 11 | وضعیت «dead» برای کلید مسدودشده | **تصمیم‌شده: قرنطینه، نه وضعیت dead.** `outbox_quarantine` تنها راه کنار گذاشتن یک رویداد است؛ وضعیت‌ها مشتق می‌شوند و ذخیره نمی‌شوند (ADR 0024 D7.12) | — | مالک Ledger |
| 12 | index جزئی `(message_key, outbox_id)` روی outbox | **پیگیری عملکرد، نه تصمیم DDL**: ابتدا انتخاب سر هر کلید را بنچمارک کنید؛ index را فقط با اندازه‌گیری لازم اضافه کنید | بنچمارک زیر بار | مالک Ledger |
| 13 | رویداد settlement در حالت OPEN | **تصمیم‌شده: `SettlementOpened` با sequence 1** (ردیف OPEN هنگام ساخت batch) | — | مالک Ledger و Settlement |
| 14 | `event_sequence` در پاسخ‌های read API | در قرارداد پیش‌نویس نیست؛ مصرف‌کننده برای resync به آن نیاز دارد | ویرایش proto (§13.1) | مالک Ledger و api-dev |
| 15 | فهرست دلایل قرنطینه و استفادهٔ `ledger_repair` برای آن | مجموعهٔ کنترل‌شدهٔ `reason_code` و قواعد metadata تصمیم شد (I-43)؛ استفادهٔ `ledger_repair` برای قرنطینه هنوز پیشنهاد است | بازبینی امنیت و عملیات | مالک Ledger و امنیت |
| 16 | فیلدهای payload رویداد | envelope تصمیم شد (ADR 0024 D2)؛ محتوای payload کسب‌وکار هنوز باز است | بازبینی قرارداد events | مالک Ledger |

### 20.2 نیازمند بنچمارک

- هزینهٔ trigger I-4 در commit، trigger `trg_lp_guard` و FK به `account_snapshots` برای هر posting
- MultiXact ناشی از `FOR KEY SHARE` روی حساب سیستمی داغ، در برابر advisory lock
- `NUMERIC` در برابر `bigint`
- RLS
- قفل جداگانه برای هر statement در برابر یک statement
- اثر UPDATE دیرهنگام برای حساب fee (همراه اثبات نبود deadlock)
- latency commit با replica همگام؛ تمرین failover و restore برای RTO
- throughput و lag relay؛ زمان اجرای reconciliation

### 20.3 ریسک‌ها

| ریسک | شدت | کاهش |
|---|---|---|
| race بین محدودیت Account بیرونی و posting (§9.3). محدودیت Wallet یا Bucket محلی است و race ندارد | بالا (قانونی) | fence همگام برای هر تغییر محدودکننده و قرارداد صریح |
| از دست رفتن Oracle Immutable Tables (تاریخی) | متوسط | trigger، audit و بررسی زنجیره؛ superuser با break-glass |
| بازطراحی قرارداد هنوز در proto اعمال نشده (`buf` نصب نیست) | متوسط | فهرست ویرایش §13.1 و spike عمودی |
| گره خوردن jOOQ OSS به «latest PostgreSQL» | کم تا متوسط | CI و سیاست ارتقا (§20.4 مورد 5) |
| نبود عدد بار | بالا | بنچمارک پیش از تعیین ظرفیت |

### 20.4 Challenges to the owner decisions

فقط مواردی که مشکل مشخص دارند. هر کدام راه‌حل پیشنهادی در ADR مربوط دارد؛ مالک می‌تواند آن را بپذیرد یا رد کند.

1. **کاهش سقف اضافه‌برداشت یا حذف مجوز باید از fence بگذرد** (تأیید شد). با cache تا 30 ثانیه و فقط رویداد،
   سقفی که Account Service پس گرفته تا «عمر cache + lag رویداد» هنوز اعطا می‌شود. تصمیم 10 مالک «سایر تغییرهای
   محدودکننده» را شامل می‌کند؛ ADR 0022 این را صریح کرده است: کاهش سقف، حذف asset و حذف مجوز debit/credit
   همگی fence دارند.
2. **Bucket مشتری یکتا روی (حساب، asset) یعنی Wallet دوم همان مالک حساب جدا لازم دارد** (تأیید شد). saga
   شناسهٔ Wallet را به Account Service می‌فرستد تا برای هر Wallet حساب جدا بدهد. اگر حسابی برگرداند که به Wallet
   دیگری بسته است، saga با `ACCOUNT_ALREADY_BOUND` شکست می‌خورد. یکتایی روی همهٔ وضعیت‌هاست، پس پس از بستن
   Bucket هم آن (حساب، asset) آزاد نمی‌شود.
3. **واریز «system-originated» به Wallet معلق به منشأ جعل‌نشدنی نیاز دارد** (تأیید شد). origin هرگز فیلد
   درخواست نیست: `SYSTEM` از scope `transactions:system`، `EXCEPTIONAL` از scope `transactions:exceptional` به
   همراه `approval_ref` و `reason_code` (CHECK در DB، S11b). `policy_ref` را سرور از policy پیکربندی‌شده تعیین
   می‌کند (ADR 0021).
4. **RPO=0 با replication همگام بر latency نوشتن و رفتار failover اثر دارد** (تأیید شد، بدون عدد). هر commit
   منتظر flush روی standby می‌ماند. اگر standby همگام نباشد، یا نوشتن متوقف می‌شود یا RPO کاهش می‌یابد؛ ADR 0014
   توقف نوشتن را انتخاب کرده است. اثر latency یک مورد بنچمارک است (§20.2).
5. **قید «latest versions» در jOOQ OSS در برابر استقرار طولانی PostgreSQL 18** (تأیید شد). اگر تولید روی 18
   بماند و jOOQ پشتیبانی OSS آن را کنار بگذارد، ارتقای jOOQ بدون مجوز تجاری ممکن نیست. کاهش: job CI روی جفت
   pin‌شده، سیاست ارتقای PostgreSQL، و بررسی صفحهٔ مجوز در هر ارتقای jOOQ (ADR 0014).
6. **انقضای hold در برابر نتیجهٔ نامعلوم provider** (تازه). تصمیم 7 انقضا را خودکار می‌کند و تصمیم 4 آزادسازی
   به دلیل نتیجهٔ نامعلوم را منع می‌کند. اگر نتیجهٔ یک پرداخت تا بعد از `expires_at` نامعلوم بماند، انقضا پولی را
   آزاد می‌کند که شاید provider پرداخته باشد. پیشنهاد: TTL policy برای holdهای پرداخت باید از پنجرهٔ inquiry
   provider بیشتر باشد (ADR 0023)؛ گزینهٔ دیگر یک عملیات مجاز «تمدید انقضا» است که در فهرست عملیات مالک نیست و
   تصمیم مالک لازم دارد.
7. **seal و Bucketهای باز** (تازه). شرط‌های seal مالک دربارهٔ Bucket چیزی نمی‌گویند. Bucketی که پس از بسته
   شدن حساب `ACTIVE` بماند هر posting را رد می‌کند و اگر حساب بعداً دوباره باز شود دوباره زنده می‌شود. ADR 0022
   پیشنهاد می‌کند seal موفق Bucketهای آن حساب را در همان تراکنش ببندد، حتی Bucket اصلی یک Wallet باز.

---

## 21. Final Recommendation

1. تصمیم‌های مالک در 2026-11-15 گرفته شدند و ثبت شدند؛ سپس 30 پیش‌نویس `Proposed` (old 0014 تا old 0043) در
   سیزده ADR، 0014 تا 0026، ادغام شدند (همگی `Proposed`؛ نقشه در `docs/adr/README.md`). مالک متن ADRها را می‌خواند و آن‌ها را می‌پذیرد؛ پس از آن وضعیت
   ADRهای جایگزین‌شده `Superseded by NNNN` می‌شود.
2. بخش‌هایی که از پیش‌نویس‌های old 0015، old 0023، old 0026، old 0030، old 0031 و old 0032 آمده‌اند (اکنون در ADRهای 0019، 0020، 0017 و 0026) با اصلاحات جزئی برای **پذیرش** به مالک پیشنهاد شوند.
3. **Spike عمودی**: PostgreSQL 18، Flyway و jOOQ (نسخه‌های BOM، pin‌شده و در CI)، DDL §7.3، `PostTransaction` با
   قفل مرتب، triggerهای I-4 و `trg_lp_guard`، RLS، تست‌های همروندی (کلید تکراری، A↔B، reversal همزمان، fence
   همزمان با posting)، saga provisioning با یک Account Service جعلی، و یک relay ساده به Kafka. همچنین harness بار
   با سناریوهای `distributed`، `hot` و `fee`. **اعداد ظرفیت فقط پس از این spike تعیین شوند.**
4. سپس hold، fence حساب، FX و settlement به همین ترتیب. پس از آن مهاجرت shadow از Sarv (§18).
5. تا آن زمان Ledger در Sarv مرجع می‌ماند. درس‌های Sarv در تست‌های Kimia بازتولید شوند.
6. follow-upهای بیرون از این بازنگری: ویرایش protoها (§13.1)، `ArchitectureTest` (`org.jooq..` و
   `org.postgresql..` فقط در `persistence.postgres..`، قاعدهٔ Kafka)، فایل‌های agent در `.claude/`، و pin نسخه‌های
   jOOQ.

**آنچه این سند ادعا نمی‌کند**: تنها چیزهایی که اجرا شدند: DDL §7.3 با smoke test کارکردی روی PostgreSQL 18.7
(2026-11-15، §2.2) و یک `./gradlew test` در 2026-11-14 که compile آن موفق بود ولی اجرای تست‌هایش بی‌نتیجه ماند.
هیچ تست همروندی، بار، Flyway، jOOQ یا Kafka اجرا نشده است. مسیر نوشتن، saga، hold، fence، seal و relay فقط طراحی
هستند. نسخه‌هایی که با «unverified» آمده‌اند تأیید نشده‌اند.

### منابع وب (خوانده‌شده در 2026-11-14)

- jOOQ editions and licence: https://www.jooq.org/download/
- Spring Boot system requirements (4.1.2): https://docs.spring.io/spring-boot/system-requirements.html
- Maven Central metadata: `repo1.maven.org/maven2/{org/jooq/jooq, org/postgresql/postgresql, org/apache/kafka/kafka-clients, org/springframework/kafka/spring-kafka, org/flywaydb/flyway-core, org/springframework/boot/spring-boot-dependencies}/maven-metadata.xml` و `spring-boot-dependencies-4.1.2.pom`
- pgJDBC: https://jdbc.postgresql.org/
- PostgreSQL 18 `SELECT` (locking privileges): https://www.postgresql.org/docs/18/sql-select.html
- PostgreSQL 18 UUID functions: https://www.postgresql.org/docs/18/functions-uuid.html
- Flyway PostgreSQL: https://documentation.red-gate.com/fd/postgresql-database-277579325.html
- Kafka releases: https://kafka.apache.org/community/downloads/
- KIP-896: https://cwiki.apache.org/confluence/display/KAFKA/KIP-896:+Remove+old+client+protocol+API+versions+in+Kafka+4.0
- PostgreSQL 18 minor (secondary): https://aws.amazon.com/about-aws/whats-new/2026/05/amazon-rds-postgresql/
