# بازبینی مستقل معماری — Kimia Financial Ledger

تاریخ: 2026-11-14 · وضعیت: **پیشنهاد برای تصمیم مالک**. هیچ ADR با این سند `Accepted` نمی‌شود.
موضوع بازبینی: شاخهٔ `docs/arch-decisions` در commit `7604c2b` به‌اضافهٔ تغییرات commit‌نشدهٔ working tree
(ADRهای 0014 تا 0032، همگی `Proposed`). منبع مقایسه: مخزن Sarv در commit `a49a842`، فقط خواندنی.

برچسب‌هایی که در کل سند به کار می‌روند:

| برچسب | معنی |
|---|---|
| **[توجیه فنی]** | از شواهد یا مستندات رسمی نتیجه می‌شود. تأیید مالک فقط تشریفاتی است |
| **[تأیید مالک]** | یک انتخاب یا مبادله (trade-off) است. مالک باید تصمیم بگیرد |
| **[بنچمارک]** | پیش از پذیرش، اندازه‌گیری روی سخت‌افزار و داده‌های هدف لازم است |
| **[مسدود: اطلاعات]** | اطلاعات لازم وجود ندارد. سند عدد یا فرض جعل نمی‌کند |
| **NOT DECIDED** | تصمیم گرفته نشده است. در §16 و §20 فهرست شده است |

---

## 1. Executive Summary

**وضع فعلی.** Kimia هنوز پیاده‌سازی ندارد و فقط مستندات و اسکلت پروژه دارد. ماژول‌های `ledger-core`،
`ledger-application`، `ledger-persistence-oracle`، `ledger-api-rest` و `ledger-api-grpc` جز
`package-info.java` چیزی ندارند. تنها تست‌ها `ArchitectureTest` و `HealthEndpointTest` هستند. ADRهای
0014 تا 0032 هم کیفیت خوبی دارند و هم درونی سازگارند، ولی روی **پیش‌فرض‌هایی** بنا شده‌اند که baseline
جدید آن‌ها را عوض می‌کند:

1. پیش‌تر Ledger مالک Account بود (`CreateAccount`، `account.proto`، ADR 0025). اکنون **Account متعلق به
   یک Account Service بیرونی است**، ولی **Wallet و BalanceBucket متعلق به Ledger هستند** (تصمیم مالک).
   Wallet و Account دو مفهوم جدا هستند.
2. پیش‌تر Oracle تنها پایگاه داده بود (ADR 0028). اکنون PostgreSQL 18 است.
3. پیش‌تر v1 رویداد و outbox نداشت (ADR 0021). اکنون Kafka با Outbox و Inbox است.
4. پیش‌تر hold بومی نبود (ADR 0022). اکنون hold یک قابلیت بومی Ledger است و به Wallet/Bucket بسته است.
5. پیش‌تر مانده فقط به صورت insert-only روی posting بود (ADR 0020). اکنون یک projection تراکنشیِ
   قابل‌تغییر وجود دارد.

**نتیجهٔ کلی.** از 19 ADR پیشنهادی: 6 **ACCEPT**، 9 **REVISE**، 4 **REJECT**، بدون DEFER (§19). کار
قبلی که ارزش نگه‌داشتن دارد: شمارهٔ ترتیبی بی‌فاصله (اکنون برای هر «position» مانده)، معنای idempotency،
قرارداد خطا، port تراکنش، ماژول contracts و انجماد قرارداد.

**مهم‌ترین توصیه‌ها**

1. **[تأیید مالک] مدل Wallet/Bucket/Account** (§4.2): یک Wallet چند Bucket دارد (شاهد Sarv). هر Bucket
   دقیقاً یک asset و دقیقاً یک `external_account_id` دارد و این دو تغییرناپذیرند. Bucket **زیرحساب
   (sub-ledger)** همان Account بیرونی است، یعنی Account طرف double-entry است و Bucket جایی است که
   موجودی مشتری نگه داشته می‌شود. دو چیز هنوز **NOT DECIDED** است: آیا چند Bucket یا چند Wallet می‌توانند
   به یک Account اشاره کنند (حساب تجمیعی یا omnibus)، یا هر مشتری Account خودش را دارد. schema هر دو را
   پشتیبانی می‌کند.
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
5. **[توجیه فنی]** ADR 0028 (فقط Oracle) رد می‌شود، به این شرط که مالک تأیید کند Oracle دیگر الزام
   استقرار نیست. jOOQ OSS با مجوز Apache-2.0 روی PostgreSQL مجاز است، با قید «latest versions» (§5).
6. **[توجیه فنی]** hold بومی است: روی Bucket، با capture و release جزئی. وضعیت فعلی در `holds` و تاریخچهٔ
   تغییرناپذیر در `hold_events` نگه داشته می‌شود (§8).
7. **[تأیید مالک]** Payment Timeline به Payments/Integration تعلق دارد و یک read model از نوع Operations
   آن را تجمیع می‌کند (§10).
8. **[مسدود: اطلاعات]** انتخاب `bigint` یا `NUMERIC(38,0)` به فهرست asset‌ها بستگی دارد (§7.2). انتخاب
   Java یا .NET تصمیم مالک است (§17). مهاجرت تدریجی است، با shadow، و برای هر جریان یک مالک واحد (§18).

### 1.1 جدول مالکیت در معماری نهایی

| مفهوم | مالک |
|---|---|
| Wallet | **Ledger** |
| BalanceBucket | **Ledger** |
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
| ADR | `docs/adr/0001`…`0032`، `docs/adr/README.md` |
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
| DDL §7.3 (نسخهٔ نهایی، با Wallet و Bucket و position) روی containerهای موقت `postgres:18` (PostgreSQL **18.7**، image digest `sha256:b9f6b8ff…`). پس از هر اجرا container حذف شد | اجرا با `ON_ERROR_STOP=1` **بدون خطا**: **24 جدول**، **43 trigger** غیرداخلی، **60 policy**. یک smoke test دستی **فقط کارکردی، بدون همروندی و بدون بار**، که همهٔ موارد مطابق انتظار رفتار کردند: S1) دو Wallet که Bucketهایشان به **یک** Account بیرونی اشاره می‌کنند (N:1) پذیرفته شدند. درج دوم position مستقیم با `ON CONFLICT … WHERE balance_bucket_id IS NULL` به `INSERT 0 0` رسید. S2) تراکنش سه‌پایه‌ای متعادل (بدهکار Bucket، بستانکار Bucket دیگر، بستانکار position مستقیم fee) با `ledger_app` commit شد. S3a) position دوم برای یک Bucket با `ux_pos_bucket` رد شد. S3b) position‌ای که Account آن با Account Bucket فرق دارد با FK نگاشت رد شد. S4) Bucket اصلی دوم برای همان Wallet و asset با `ux_bucket_primary` رد شد. S5) تغییر `external_account_id` یک Bucket با trigger هویتی رد شد. S6) UPDATE وضعیت Wallet مجاز بود، ولی `CLOSED` بدون `closed_at` با CHECK رد شد. S7) hold روی position مستقیم با FK رد شد. S8) hold روی position یک Bucket پذیرفته شد. S9) تراکنش نامتعادل در COMMIT با trigger I-4 رد شد. S10) posting با `wallet_id` ولی بدون Bucket با CHECK رد شد. S11) UPDATE و DELETE روی postingها و DELETE روی `wallets` برای `ledger_app` «permission denied» گرفتند. S12) بدون `app.tenant_id` صفر ردیف برگشت، و درج برای tenant دیگر با RLS رد شد. S13) reversal دوم با `ux_ltx_single_reversal` رد شد. S14) حتی superuser هم با trigger متوقف شد: UPDATE روی posting، DELETE روی `wallets`، و TRUNCATE روی `bucket_status_events`. S15) مجموع positionهای یک Account برابر مانده‌های Bucketها بود (−990). **اجرا نشد**: قفل‌گیری همزمان، deadlock، و کارایی. در اجرای اول، نسخهٔ پیشین DDL (20 جدول) هم بدون خطا اجرا شده بود و همچنین تأیید شد که درج در ستون identity به grant روی sequence نیاز ندارد |
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
| قرارداد | `ledger_service.proto` شامل این‌هاست: `CreateAccount/GetAccount/PostEntry/GetEntry/GetBalance/ListPostings`. هنوز `sequence_no`، `correlation_id` به صورت field و کد HTTP 422 را دارد، یعنی اصلاحات ADR 0014 و 0018 هنوز در proto اعمال نشده‌اند |
| پایگاه داده | هیچ migration یا DDL وجود ندارد. هیچ SQL‌ای در مخزن نیست |
| نکتهٔ مهم | خود `CLAUDE.md` هنوز «update balances → insert outbox row» را توصیف می‌کند، یعنی با baseline جدید هم‌خوان است و با ADRهای 0020 و 0021 در تعارض است. بنابراین مستندات فعلی ناسازگارند و مالک باید یکی را برگزیند |

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
| پایگاه داده | SQL Server، EF Core 10 | Oracle (ADR 0028) | PostgreSQL 18، jOOQ و JDBC |
| همروندی | RowVersion خوش‌بینانه، 8 تلاش، full-jitter (8ms تا 200ms) | قفل‌های بدبینانه با ترتیب مرتب | بدبینانه برای حساب‌های مشتری و ترکیبی برای حساب‌های داغ (§11) |
| idempotency | `(ReferenceId, OperationType)`، بدون hash و بدون ثبت رد | کلید واحد برای هر tenant، hash payload، ثبت ردهای وابسته به وضعیت | ADR 0017 با اصلاحات PostgreSQL |
| مانده | `BalanceBucket` قابل‌تغییر. حساب سیستمی با `+= delta` | `balance_after` فقط insert | projection قابل‌تغییر به‌اضافهٔ `balance_after` روی posting |
| hold | فقط capture کامل. state قابل‌تغییر بدون تاریخچه | hold بومی ندارد (ADR 0022) | hold بومی، جزئی، با `hold_events` |
| تغییرناپذیری | فقط در سطح کد. cascade delete وجود دارد | grant و Oracle Immutable Tables | grant، trigger، audit، بدون cascade |
| رویداد | Wolverine با durable local queue (outbox روی SQL Server) | در v1 ندارد | Outbox و Inbox روی Kafka |
| چند ارز | فقط IRR | چند ارز، با FX به دست caller | چند asset، با snapshot از FX quote |
| مالکیت | Ledger مالک Wallet، Bucket، `LedgerAccount` و `SystemAccount` است | Ledger مالک Account است و Wallet ندارد | Ledger مالک Wallet و Bucket است. Account، از جمله حساب سیستمی، بیرونی است (§4) |
| tenant | ندارد | از روز اول | از روز اول. RLS به‌عنوان لایهٔ دوم |

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
| Bucket → Account بیرونی | **هر Bucket دقیقاً یک `external_account_id` دارد که تغییرناپذیر است**. Account طرف double-entry است (طبقه‌بندی، normal side، مجوزها) و Bucket زیرحساب آن است | [توجیه فنی]. بدون آن posting روی Bucket معنای حسابداری ندارد |
| Account → Bucket | **NOT DECIDED**. گزینهٔ (a): **1:1**، یعنی Account Service برای هر Bucket یک Account می‌سازد (مثل `LedgerAccount` در Sarv، ولی بیرونی). گزینهٔ (b): **N:1 برای هر مشتری**، یعنی چند Bucket یک مشتری به یک Account آن مشتری اشاره می‌کنند. گزینهٔ (c): **N:1 تجمیعی (omnibus)**، یعنی همهٔ Bucketهای یک محصول یا asset به یک Account «سپردهٔ مشتریان» اشاره می‌کنند. **توصیه: schema هر سه را پشتیبانی می‌کند. پیش‌فرض پیشنهادی (b) است**. دلیل: (a) برای هر Bucket یک فراخوانی بین‌سرویسی لازم دارد، و (c) seal را روی میلیون‌ها Bucket غیرعملی می‌کند و با قفل anchor یک hot-spot می‌سازد (§9) | [تأیید مالک] |
| چند Wallet → یک Account | در (c) بله، در (a) و (b) معمولاً نه. schema منع نمی‌کند | NOT DECIDED، تابع سطر بالا |
| Wallet → مالک | `owner_ref` یک شناسهٔ بیرونی opaque است، بدون PII. Sarv یک Wallet برای هر مشتری دارد. Kimia: unique روی `(tenant, owner_ref, wallet_type)` برای Walletهای باز | NOT DECIDED: چند Wallet برای هر مالک |
| حساب‌های سیستمی | postingها مستقیماً به «Account بیرونی + asset» می‌خورند (position بدون Bucket). Wallet ندارند | [توجیه فنی] |
| کاتالوگ محصول Bucket (MAIN، VOUCHER و غیره) | در Ledger به صورت `product_code` نگه داشته می‌شود. محتوای کاتالوگ و جدول آن NOT DECIDED است | [تأیید مالک] |

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
| Java 25 و Spring Boot 4.x | **[توجیه فنی]** سازگاری مستند است (§5.3). ADR 0001 قبلاً پذیرفته شده است |
| PostgreSQL 18 | **[توجیه فنی]** با این شرط که مالک تأیید کند Oracle دیگر الزام استقرار نیست ([تأیید مالک]). مزایای مشخص: partial unique index با معنای درست NULL، constraint trigger از نوع deferred برای enforce کردن I-4، RLS بدون وابستگی به edition، تابع `uuidv7()` |
| jOOQ و JDBC | **[تأیید مالک]**. شرح در §5.2 |
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

**توصیه:** jOOQ پذیرفتنی است و `CLAUDE.md` باید عوض شود (§19.2). شرط‌ها:

1. `org.jooq..` فقط در ماژول persistence مجاز باشد (قاعدهٔ ArchUnit).
2. همهٔ دستورهای قفل‌گیری (`FOR UPDATE`، `FOR KEY SHARE`، advisory lock) در یک کلاس متمرکز باشند و
   integration test داشته باشند.
3. یک spike روی PostgreSQL 18، jOOQ 3.21.9 و pgJDBC 42.7.14 انجام شود.

اگر مالک ریسک گره‌خوردن نسخه‌ها را نپذیرد، `JdbcClient` (وضع فعلی ADR 0002) بی‌خطر است.

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
| gRPC و protobuf | BOM: grpc-java 1.84.1، protobuf-java 4.36.2 | BOM | فقط به‌عنوان اطلاع. مجوزها طبق ADR 0031 بررسی شوند |
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
    ...core.money                    Money, AssetCode, Amount (BigInteger|long — §7.2)
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
    ...application.wallet            CreateWallet, Suspend/Activate/CloseWallet, Create/Suspend/CloseBucket, balances
    ...application.account           ApplyAccountState (fence/seal), AccountResolver (cache→remote)
    ...application.port.out          LedgerTransactions (ADR 0030), AccountDirectory, Outbox, Clock, IdGenerator
  ledger-contracts/                  com.kimialedger.contracts       generated stubs + mapping (ADR 0031)
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

- **[توجیه فنی]** همهٔ کلیدها با `tenant_id` شروع می‌شوند و FKها composite هستند (ADR 0027). هیچ
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
- **[توجیه فنی] Posting** به `position_id` اشاره می‌کند. برای خوانایی و audit، ستون‌های
  `external_account_id`، `asset_code`، `balance_bucket_id` و `wallet_id` هم در خود posting تکرار
  می‌شوند و این تکرار با FK به position و Bucket بررسی می‌شود. تعادل I-4 در هر asset روی **همهٔ**
  postingهای تراکنش محاسبه می‌شود و به Bucket یا مستقیم بودن position بستگی ندارد.
- شناسه‌ها `uuid` هستند و در برنامه ساخته می‌شوند (UUIDv7). `outbox_id` یک identity است.

### 7.2 پول: `bigint` یا `NUMERIC(38,0)`

| معیار | `bigint` و `long` | `NUMERIC(38,0)` و `BigInteger` |
|---|---|---|
| بازه | ±9.22×10^18 واحد خرد | تا 10^38−1 |
| IRR با scale 0 (Sarv: `CurrencyFraction` IRR=1) | کافی است | کافی است |
| asset با scale 18، مثلاً `wei` | **ناکافی**: 2^63 wei حدود 9.22 واحد است | کافی است |
| کارایی | سریع‌تر است. **[بنچمارک]** | محاسبهٔ نرم‌افزاری. هزینه اندازه‌گیری نشده است |
| تغییر بعدی | تغییر نوع ستون روی جدول append-only و بزرگ به معنای بازنویسی جدول است | — |

**توصیه:** **[مسدود: اطلاعات]** فهرست asset‌های مورد پشتیبانی و scale هر کدام باید مشخص شود. وجود
`src/Integrations/Bitpin` در Sarv احتمال رمزارز را بالا می‌برد. DDL زیر **`NUMERIC(38,0)`** را برای
ذخیره‌سازی در نظر گرفته است، چون تغییر بعدی گران است. سقف هر asset در `assets.max_amount` ثبت و enforce
می‌شود. روی wire مبلغ به صورت **decimal string** فرستاده می‌شود. در Java یک `Amount` تغییرناپذیر با
`BigInteger` و عملیات دقیق ساخته می‌شود. اگر دامنهٔ asset‌ها فقط fiat باشد، می‌توان همهٔ ستون‌ها را
`bigint` کرد و ADR 0019 را با int64 نگه داشت. در هیچ حالتی float یا double استفاده نمی‌شود.

### 7.3 DDL کامل (PostgreSQL 18)

```sql
-- ============ 0. Roles (run once by DBA; passwords/certs from Vault, never in this file) ============
CREATE ROLE ledger_owner     NOLOGIN;                 -- owns all objects
CREATE ROLE ledger_migrator  LOGIN IN ROLE ledger_owner;   -- Flyway only; initSql: SET ROLE ledger_owner
                                                           -- so objects are owned by ledger_owner, not the login
CREATE ROLE ledger_app       LOGIN;                   -- runtime (API + workers)
CREATE ROLE ledger_relay     LOGIN;                   -- outbox relay
CREATE ROLE ledger_readonly  LOGIN;                   -- reconciliation, audit jobs, reporting
CREATE ROLE ledger_operator  LOGIN;                   -- human-approved operational procedures

CREATE SCHEMA ledger AUTHORIZATION ledger_owner;
REVOKE ALL ON SCHEMA ledger FROM PUBLIC;
SET search_path = ledger;

-- ============ 1. Reference data ============
CREATE TABLE tenants (
  tenant_id     uuid        PRIMARY KEY,
  issuer        text        NOT NULL,
  tenant_claim  text        NOT NULL,
  status        text        NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED')),
  created_at    timestamptz NOT NULL DEFAULT now(),
  UNIQUE (issuer, tenant_claim)                                  -- ADR 0023
);

CREATE TABLE assets (                                             -- immutable; new scale = new code
  asset_code   varchar(16)   PRIMARY KEY CHECK (asset_code ~ '^[A-Z0-9][A-Z0-9_.-]{1,15}$'),
  asset_kind   text          NOT NULL CHECK (asset_kind IN ('FIAT','CRYPTO','OTHER')),
  scale        smallint      NOT NULL CHECK (scale BETWEEN 0 AND 18),
  max_amount   numeric(38,0) NOT NULL CHECK (max_amount > 0),
  created_at   timestamptz   NOT NULL DEFAULT now()
);

-- ============ 2. External ACCOUNTS: non-authoritative cache + immutable snapshots (no FK points here) ============
CREATE TABLE account_state_cache (                                -- current view; lock anchor for account fencing (§9)
  tenant_id            uuid          NOT NULL REFERENCES tenants ON DELETE RESTRICT,
  external_account_id  varchar(128)  NOT NULL CHECK (external_account_id ~ '^[\x21-\x7E]+$'),
  account_version      bigint        NOT NULL CHECK (account_version >= 1),
  source_status        text          NOT NULL,                     -- Account Service vocabulary, audit only
  can_debit            boolean       NOT NULL,
  can_credit           boolean       NOT NULL,
  normal_side          char(1)       NOT NULL CHECK (normal_side IN ('D','C')),
  allow_negative       boolean       NOT NULL,
  overdraft_limit      numeric(38,0) CHECK (overdraft_limit >= 0), -- NULL = unlimited when allow_negative
  allowed_assets       varchar(16)[] NOT NULL CHECK (cardinality(allowed_assets) >= 1),
  closed               boolean       NOT NULL DEFAULT false,
  validated_at         timestamptz   NOT NULL,
  PRIMARY KEY (tenant_id, external_account_id),
  CHECK (NOT closed OR (NOT can_debit AND NOT can_credit))
);

CREATE TABLE account_snapshots (                                  -- immutable history; postings record the version used
  tenant_id            uuid          NOT NULL REFERENCES tenants ON DELETE RESTRICT,
  external_account_id  varchar(128)  NOT NULL,
  account_version      bigint        NOT NULL CHECK (account_version >= 1),
  source_status        text          NOT NULL,
  can_debit            boolean       NOT NULL,
  can_credit           boolean       NOT NULL,
  normal_side          char(1)       NOT NULL CHECK (normal_side IN ('D','C')),
  allow_negative       boolean       NOT NULL,
  overdraft_limit      numeric(38,0),
  allowed_assets       varchar(16)[] NOT NULL,
  account_class        text          NOT NULL,                     -- e.g. CUSTOMER_DEPOSITS, FEE_REVENUE (no PII)
  source               text          NOT NULL CHECK (source IN ('EVENT','LOOKUP','FENCE')),
  source_event_id      uuid,
  recorded_at          timestamptz   NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, external_account_id, account_version)
);

-- ============ 3. WALLETS and BALANCE BUCKETS (authoritative in Ledger) ============
CREATE TABLE wallets (
  tenant_id    uuid         NOT NULL REFERENCES tenants ON DELETE RESTRICT,
  wallet_id    uuid         NOT NULL,
  owner_ref    varchar(128) NOT NULL CHECK (owner_ref ~ '^[\x21-\x7E]+$'),  -- opaque external identity ref, no PII
  wallet_type  varchar(32)  NOT NULL DEFAULT 'DEFAULT',
  status       text         NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED')),
  version      bigint       NOT NULL DEFAULT 0,
  created_at   timestamptz  NOT NULL,
  updated_at   timestamptz  NOT NULL,
  closed_at    timestamptz,
  PRIMARY KEY (tenant_id, wallet_id),
  CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);
-- [NOT DECIDED] wallets per owner. Sarv: one per customer (unique CustomerId). Default here: one open wallet per (owner, type).
CREATE UNIQUE INDEX ux_wallet_owner_open ON wallets (tenant_id, owner_ref, wallet_type) WHERE status <> 'CLOSED';
CREATE INDEX ix_wallet_owner ON wallets (tenant_id, owner_ref);

CREATE TABLE wallet_status_events (                               -- immutable lifecycle history
  tenant_id        uuid         NOT NULL,
  wallet_id        uuid         NOT NULL,
  event_seq        integer      NOT NULL CHECK (event_seq >= 1),
  from_status      text,
  to_status        text         NOT NULL CHECK (to_status IN ('ACTIVE','SUSPENDED','CLOSED')),
  reason_code      text         NOT NULL,
  idempotency_key  varchar(255) NOT NULL,
  client_id        varchar(255) NOT NULL,
  correlation_id   varchar(128) NOT NULL,
  occurred_at      timestamptz  NOT NULL,
  PRIMARY KEY (tenant_id, wallet_id, event_seq),
  FOREIGN KEY (tenant_id, wallet_id) REFERENCES wallets (tenant_id, wallet_id) ON DELETE RESTRICT
);

CREATE TABLE balance_buckets (
  tenant_id            uuid          NOT NULL,
  bucket_id            uuid          NOT NULL,
  wallet_id            uuid          NOT NULL,
  asset_code           varchar(16)   NOT NULL REFERENCES assets ON DELETE RESTRICT,
  external_account_id  varchar(128)  NOT NULL,                     -- the double-entry account this bucket sub-ledgers
  product_code         varchar(32)   NOT NULL,                     -- MAIN, VOUCHER, REWARD … (catalog NOT DECIDED)
  is_primary           boolean       NOT NULL,
  sponsor_ref          varchar(128),                               -- Sarv SponsorId analogue; opaque
  status               text          NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED')),
  version              bigint        NOT NULL DEFAULT 0,
  created_at           timestamptz   NOT NULL,
  updated_at           timestamptz   NOT NULL,
  closed_at            timestamptz,
  PRIMARY KEY (tenant_id, bucket_id),
  UNIQUE (tenant_id, bucket_id, external_account_id, asset_code),  -- target for the position FK
  UNIQUE (tenant_id, bucket_id, wallet_id),                        -- target for posting/hold FKs
  FOREIGN KEY (tenant_id, wallet_id) REFERENCES wallets (tenant_id, wallet_id) ON DELETE RESTRICT,
  CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);
CREATE UNIQUE INDEX ux_bucket_primary ON balance_buckets (tenant_id, wallet_id, asset_code)
  WHERE is_primary AND status <> 'CLOSED';                          -- Sarv: one primary per wallet+currency
CREATE INDEX ix_bucket_wallet  ON balance_buckets (tenant_id, wallet_id);
CREATE INDEX ix_bucket_account ON balance_buckets (tenant_id, external_account_id, asset_code);

CREATE TABLE bucket_status_events (                               -- immutable lifecycle history
  tenant_id        uuid         NOT NULL,
  bucket_id        uuid         NOT NULL,
  event_seq        integer      NOT NULL CHECK (event_seq >= 1),
  from_status      text,
  to_status        text         NOT NULL CHECK (to_status IN ('ACTIVE','SUSPENDED','CLOSED')),
  reason_code      text         NOT NULL,
  idempotency_key  varchar(255) NOT NULL,
  client_id        varchar(255) NOT NULL,
  correlation_id   varchar(128) NOT NULL,
  occurred_at      timestamptz  NOT NULL,
  PRIMARY KEY (tenant_id, bucket_id, event_seq),
  FOREIGN KEY (tenant_id, bucket_id) REFERENCES balance_buckets (tenant_id, bucket_id) ON DELETE RESTRICT
);

-- ============ 4. Balance positions: THE ONLY balance store ============
CREATE TABLE balance_projections (
  tenant_id            uuid          NOT NULL REFERENCES tenants ON DELETE RESTRICT,
  position_id          uuid          NOT NULL,
  external_account_id  varchar(128)  NOT NULL,
  asset_code           varchar(16)   NOT NULL REFERENCES assets ON DELETE RESTRICT,
  balance_bucket_id    uuid,                                       -- NULL = direct position (system account)
  posted_balance       numeric(38,0) NOT NULL DEFAULT 0,             -- signed, debit-positive (I-9)
  reserved_amount      numeric(38,0) NOT NULL DEFAULT 0 CHECK (reserved_amount >= 0),
  last_position_seq    bigint        NOT NULL DEFAULT 0 CHECK (last_position_seq >= 0),
  active_hold_count    integer       NOT NULL DEFAULT 0 CHECK (active_hold_count >= 0),
  version              bigint        NOT NULL DEFAULT 0,
  updated_at           timestamptz   NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, position_id),
  UNIQUE (tenant_id, position_id, external_account_id, asset_code),
  UNIQUE (tenant_id, position_id, balance_bucket_id),
  FOREIGN KEY (tenant_id, balance_bucket_id, external_account_id, asset_code)       -- bucket mapping must agree
    REFERENCES balance_buckets (tenant_id, bucket_id, external_account_id, asset_code) ON DELETE RESTRICT,
  CHECK (balance_bucket_id IS NOT NULL OR (reserved_amount = 0 AND active_hold_count = 0))  -- holds only on buckets
);
CREATE UNIQUE INDEX ux_pos_bucket ON balance_projections (tenant_id, balance_bucket_id)
  WHERE balance_bucket_id IS NOT NULL;                              -- one position per bucket
CREATE UNIQUE INDEX ux_pos_direct ON balance_projections (tenant_id, external_account_id, asset_code)
  WHERE balance_bucket_id IS NULL;                                  -- one direct position per account+asset
CREATE INDEX ix_pos_account ON balance_projections (tenant_id, external_account_id, asset_code);
-- wallet balance  = Σ positions of its buckets (per asset);  account balance = Σ positions with that account+asset.
-- Neither is stored: no duplicated balances.

-- ============ 5. Journal (append-only) ============
CREATE TABLE ledger_transactions (
  tenant_id                uuid          NOT NULL REFERENCES tenants ON DELETE RESTRICT,
  transaction_id           uuid          NOT NULL,
  transaction_type         text          NOT NULL CHECK (transaction_type IN
                             ('STANDARD','REVERSAL','REFUND','HOLD_CAPTURE','FX','SETTLEMENT',
                              'ADJUSTMENT','OPENING_BALANCE')),
  idempotency_key          varchar(255)  NOT NULL CHECK (idempotency_key ~ '^[\x21-\x7E]{1,255}$'),
  request_hash             bytea         NOT NULL CHECK (octet_length(request_hash) = 32),
  external_ref             varchar(255),
  reverses_transaction_id  uuid,
  related_transaction_id   uuid,
  hold_id                  uuid,
  fx_quote_id              uuid,
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
  CHECK (reverses_transaction_id IS DISTINCT FROM transaction_id)
);
CREATE UNIQUE INDEX ux_ltx_single_reversal ON ledger_transactions (tenant_id, reverses_transaction_id)
  WHERE reverses_transaction_id IS NOT NULL;                        -- replaces ADR 0016's link table
CREATE UNIQUE INDEX ux_ltx_fx_quote_once  ON ledger_transactions (tenant_id, fx_quote_id)
  WHERE fx_quote_id IS NOT NULL;                                    -- single-use quote [owner]
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
  position_seq         bigint        NOT NULL CHECK (position_seq >= 1),        -- ADR 0014, per position
  balance_after        numeric(38,0) NOT NULL,                                  -- of the position, signed (I-9)
  account_version      bigint        NOT NULL CHECK (account_version >= 1),     -- account snapshot used
  posted_at            timestamptz   NOT NULL,
  effective_at         timestamptz   NOT NULL,
  PRIMARY KEY (tenant_id, posting_id),
  UNIQUE (tenant_id, transaction_id, line_no),
  UNIQUE (tenant_id, position_id, position_seq),                                -- gapless per position
  FOREIGN KEY (tenant_id, transaction_id)
    REFERENCES ledger_transactions (tenant_id, transaction_id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, position_id, external_account_id, asset_code)
    REFERENCES balance_projections (tenant_id, position_id, external_account_id, asset_code) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, position_id, balance_bucket_id)
    REFERENCES balance_projections (tenant_id, position_id, balance_bucket_id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, balance_bucket_id, wallet_id)
    REFERENCES balance_buckets (tenant_id, bucket_id, wallet_id) ON DELETE RESTRICT,
  CHECK ((balance_bucket_id IS NULL) = (wallet_id IS NULL))
);
-- Gap: a posting on a bucket position with balance_bucket_id NULL passes the MATCH SIMPLE FKs.
-- Caught by core + reconciliation (§16.3); a BEFORE INSERT trigger is the stricter option [benchmark].
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

-- ============ 6. Holds (always on a bucket position) ============
CREATE TABLE holds (                                               -- current state (mutable)
  tenant_id            uuid          NOT NULL REFERENCES tenants ON DELETE RESTRICT,
  hold_id              uuid          NOT NULL,
  position_id          uuid          NOT NULL,
  balance_bucket_id    uuid          NOT NULL,
  wallet_id            uuid          NOT NULL,
  amount               numeric(38,0) NOT NULL CHECK (amount > 0),
  captured_amount      numeric(38,0) NOT NULL DEFAULT 0 CHECK (captured_amount >= 0),
  released_amount      numeric(38,0) NOT NULL DEFAULT 0 CHECK (released_amount >= 0),
  status               text          NOT NULL CHECK (status IN ('ACTIVE','CAPTURED','RELEASED','EXPIRED')),
  expires_at           timestamptz   NOT NULL,
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
  CHECK (captured_amount + released_amount <= amount),
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
CREATE TABLE idempotency_records (                                  -- append-only; one key space per tenant (ADR 0017)
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

-- ============ 8. FX ============
CREATE TABLE fx_quotes (                                            -- immutable snapshot of the quote used
  tenant_id       uuid           NOT NULL REFERENCES tenants ON DELETE RESTRICT,
  fx_quote_id     uuid           NOT NULL,
  source_asset    varchar(16)    NOT NULL REFERENCES assets,
  target_asset    varchar(16)    NOT NULL REFERENCES assets,
  rate            numeric(38,18) NOT NULL CHECK (rate > 0),          -- target per source, major units; exact decimal
  mid_rate        numeric(38,18) CHECK (mid_rate > 0),
  rate_source     text           NOT NULL,
  source_ref      varchar(128)   NOT NULL,
  quoted_at       timestamptz    NOT NULL,
  valid_until     timestamptz    NOT NULL,
  rounding_mode   text           NOT NULL CHECK (rounding_mode IN ('HALF_EVEN','HALF_UP','DOWN','UP')),
  source_amount   numeric(38,0)  NOT NULL CHECK (source_amount > 0),
  target_amount   numeric(38,0)  NOT NULL CHECK (target_amount > 0),
  fee_amount      numeric(38,0)  NOT NULL DEFAULT 0 CHECK (fee_amount >= 0),
  fee_asset       varchar(16)    REFERENCES assets,
  recorded_at     timestamptz    NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, fx_quote_id),
  CHECK (source_asset <> target_asset),
  CHECK (valid_until > quoted_at),
  CHECK ((fee_amount = 0) OR fee_asset IS NOT NULL)
);
ALTER TABLE ledger_transactions
  ADD FOREIGN KEY (tenant_id, fx_quote_id) REFERENCES fx_quotes (tenant_id, fx_quote_id) ON DELETE RESTRICT;

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
                                                 'PROJECTION_DRIFT','SEQ_GAP','UNBALANCED_TX','MAPPING_MISMATCH')),
  subject_ref      varchar(255) NOT NULL,
  expected_amount  numeric(38,0), actual_amount numeric(38,0),
  detected_at      timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, discrepancy_id),
  FOREIGN KEY (tenant_id, run_id) REFERENCES recon_runs (tenant_id, run_id) ON DELETE RESTRICT
);

CREATE TABLE recon_resolutions (
  tenant_id        uuid NOT NULL,
  discrepancy_id   uuid NOT NULL,
  resolution       text NOT NULL CHECK (resolution IN ('ADJUSTED','EXPLAINED','EXTERNAL_FIX')),
  adjustment_tx_id uuid,
  approval_ref     varchar(128) NOT NULL,
  resolved_at      timestamptz NOT NULL,
  PRIMARY KEY (tenant_id, discrepancy_id),
  FOREIGN KEY (tenant_id, discrepancy_id) REFERENCES recon_discrepancies ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, adjustment_tx_id) REFERENCES ledger_transactions (tenant_id, transaction_id) ON DELETE RESTRICT,
  CHECK ((resolution = 'ADJUSTED') = (adjustment_tx_id IS NOT NULL))
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
  last_error       text
);
CREATE INDEX ix_outbox_pending ON outbox_events (outbox_id) WHERE published_at IS NULL;

CREATE TABLE inbox_events (
  consumer_name    text         NOT NULL,
  message_id       uuid         NOT NULL,
  topic            text         NOT NULL,
  kafka_partition  integer      NOT NULL,
  kafka_offset     bigint       NOT NULL,
  received_at      timestamptz  NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer_name, message_id)
);

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
  FOREACH t IN ARRAY ARRAY['assets','account_snapshots','wallet_status_events','bucket_status_events',
                           'ledger_transactions','ledger_postings','hold_events','idempotency_records',
                           'fx_quotes','settlement_batch_items','settlement_batch_events',
                           'external_statement_lines','recon_discrepancies','recon_resolutions']
  LOOP
    EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON ledger.%I
                    FOR EACH ROW EXECUTE FUNCTION ledger.forbid_mutation()', 'trg_'||t||'_no_mutation', t);
    EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON ledger.%I
                    FOR EACH STATEMENT EXECUTE FUNCTION ledger.forbid_mutation()', 'trg_'||t||'_no_truncate', t);
  END LOOP;
  -- mutable financial state: UPDATE allowed (by grant), DELETE/TRUNCATE never
  FOREACH t IN ARRAY ARRAY['wallets','balance_buckets','balance_projections','holds','settlement_batches']
  LOOP
    EXECUTE format('CREATE TRIGGER %I BEFORE DELETE ON ledger.%I
                    FOR EACH ROW EXECUTE FUNCTION ledger.forbid_mutation()', 'trg_'||t||'_no_delete', t);
    EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON ledger.%I
                    FOR EACH STATEMENT EXECUTE FUNCTION ledger.forbid_mutation()', 'trg_'||t||'_no_truncate', t);
  END LOOP;
END $$;

CREATE TRIGGER trg_wallets_identity BEFORE UPDATE ON wallets FOR EACH ROW
  EXECUTE FUNCTION ledger.forbid_identity_change('tenant_id','wallet_id','owner_ref','wallet_type','created_at');
CREATE TRIGGER trg_buckets_identity BEFORE UPDATE ON balance_buckets FOR EACH ROW
  EXECUTE FUNCTION ledger.forbid_identity_change('tenant_id','bucket_id','wallet_id','asset_code',
                                                 'external_account_id','product_code','is_primary','created_at');
CREATE TRIGGER trg_positions_identity BEFORE UPDATE ON balance_projections FOR EACH ROW
  EXECUTE FUNCTION ledger.forbid_identity_change('tenant_id','position_id','external_account_id',
                                                 'asset_code','balance_bucket_id');
CREATE TRIGGER trg_holds_identity BEFORE UPDATE ON holds FOR EACH ROW
  EXECUTE FUNCTION ledger.forbid_identity_change('tenant_id','hold_id','position_id','balance_bucket_id',
                                                 'wallet_id','amount','expires_at','created_at');

-- ============ 13. Privileges ============
GRANT USAGE ON SCHEMA ledger TO ledger_app, ledger_relay, ledger_readonly, ledger_operator;

GRANT SELECT ON tenants, assets TO ledger_app;
GRANT SELECT, INSERT ON account_snapshots, wallet_status_events, bucket_status_events,
                        ledger_transactions, ledger_postings, hold_events, idempotency_records, fx_quotes,
                        settlement_batch_items, settlement_batch_events, external_statement_lines,
                        recon_discrepancies, recon_resolutions TO ledger_app;
-- mutable state; UPDATE is also what FOR UPDATE / FOR KEY SHARE require on PostgreSQL
GRANT SELECT, INSERT, UPDATE ON account_state_cache, wallets, balance_buckets, balance_projections, holds,
                                settlement_batches, recon_runs TO ledger_app;
GRANT INSERT ON outbox_events TO ledger_app;            -- identity needs no sequence grant (checked on 18.7)
GRANT SELECT, INSERT ON inbox_events TO ledger_app;

GRANT SELECT, DELETE, UPDATE (published_at, publish_attempts, last_error) ON outbox_events TO ledger_relay;

GRANT SELECT ON ALL TABLES IN SCHEMA ledger TO ledger_readonly;    -- reconciliation runs here (detect-only)

GRANT SELECT, INSERT ON tenants TO ledger_operator;
GRANT UPDATE (status) ON tenants TO ledger_operator;               -- suspend tenant (ADR 0023)
-- Superusers bypass grants, and the owner can DISABLE TRIGGER: mitigated by pgaudit on DDL/role
-- changes, a break-glass procedure, and the reconciliation chain checks (§16.3).

-- ============ 14. Tenant isolation: RLS as second layer (ADR 0027 revised) ============
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['account_state_cache','account_snapshots','wallets','wallet_status_events',
                           'balance_buckets','bucket_status_events','balance_projections',
                           'ledger_transactions','ledger_postings','holds','hold_events','idempotency_records',
                           'fx_quotes','settlement_batches','settlement_batch_items','settlement_batch_events',
                           'recon_runs','external_statement_lines','recon_discrepancies','recon_resolutions']
  LOOP
    EXECUTE format('ALTER TABLE ledger.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE ledger.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format($p$CREATE POLICY tenant_isolation ON ledger.%I TO ledger_app
                      USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
                      WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)$p$, t);
    EXECUTE format('CREATE POLICY readonly_all ON ledger.%I FOR SELECT TO ledger_readonly USING (true)', t);
    -- FORCE applies to the owner too; without this, data migrations would silently see zero rows.
    EXECUTE format('CREATE POLICY owner_all ON ledger.%I TO ledger_owner USING (true) WITH CHECK (true)', t);
  END LOOP;
END $$;
-- Cross-tenant workers (hold expiry) iterate `tenants` (no RLS) and set app.tenant_id per tenant,
-- one tenant per transaction. The outbox/inbox tables carry no RLS; the relay is tenant-agnostic.
```

**نکته‌ها دربارهٔ DDL**

- **فهرست جدول‌ها (24 جدول)**: `tenants`، `assets`، `account_state_cache`، `account_snapshots`،
  `wallets`، `wallet_status_events`، `balance_buckets`، `bucket_status_events`، `balance_projections`،
  `ledger_transactions`، `ledger_postings`، `holds`، `hold_events`، `idempotency_records`، `fx_quotes`،
  `settlement_batches`، `settlement_batch_items`، `settlement_batch_events`، `recon_runs`،
  `external_statement_lines`، `recon_discrepancies`، `recon_resolutions`، `outbox_events`،
  `inbox_events`.
- قاعدهٔ «هر تراکنش حداکثر یک بار reverse می‌شود» با partial unique index اجرا می‌شود. جدول پیوندی
  ADR 0016 لازم نیست، چون آن جدول فقط راه دور زدن رفتار Oracle بود.
- سه چیز در DB **enforce نمی‌شود** و به `ledger-core` و reconciliation سپرده شده است: reverse کردن یک
  reversal، درستی زنجیرهٔ `balance_after`، و posting روی position یک Bucket که در آن
  `balance_bucket_id` تهی است (یادداشت داخل DDL).
- **Partitioning** [مسدود: اطلاعات]: partition زمانی، uniqueهای سراسری مثل `(position_id, position_seq)`
  و کلید idempotency را **می‌شکند**. journal هم حذف نمی‌شود. اگر partition لازم شد، روش درست
  `PARTITION BY HASH (tenant_id)` است. partition زمانی فقط برای outbox، inbox و جدول‌های timeline در
  Payments مناسب است.
- **Retention**: journal، رویدادهای وضعیت، `hold_events`، رکوردهای `ACCEPTED` در idempotency،
  `fx_quotes` و settlement حذف نمی‌شوند (نگهداشت قانونی NOT DECIDED است). outbox پس از انتشار قابل حذف
  است. نگهداری inbox و رکوردهای `REJECTED` NOT DECIDED است.

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
| اضافه‌برداشت | اگر `allow_negative=false`: `available ≥ 0` پس از آخرین posting همان position در تراکنش (ADR 0016، تصمیم 13). اگر `true`: سقف `overdraft_limit`. برای position Bucket، پیش‌فرض پیشنهادی **عدم اضافه‌برداشت** است، حتی اگر Account اجازه بدهد ([تأیید مالک]، چون این قاعدهٔ سطح Wallet است) | Account Service، و Ledger برای Bucket |
| Wallet | `CLOSED` هر posting را رد می‌کند. `SUSPENDED` برداشت را رد می‌کند. پذیرش واریز در حالت SUSPENDED **NOT DECIDED** است: در Sarv، Wallet غیرفعال هر دو طرف را رد می‌کند (`PostTransactionHandler.cs:80-85`) ولی Bucket معلق credit می‌پذیرد (`BalanceBucket.Credit`). پیشنهاد: واریز پذیرفته شود تا refund و settlement گیر نکنند | Ledger |
| Bucket | مثل Wallet. asset posting باید برابر asset Bucket باشد | Ledger |

### 8.2 PostTransaction: مسیر دقیق نوشتن

هدف هر posting در درخواست یکی از این سه است:

- `balance_bucket_id`
- `wallet_id` با asset، که به Bucket اصلی تبدیل می‌شود (رفتار `BalanceBucketResolver` در Sarv)
- `external_account_id` با asset، برای حساب سیستمی، که به position مستقیم تبدیل می‌شود

```
── خارج از تراکنش DB ─────────────────────────────────────────────
 1. mTLS + JWT → tenant → scope transactions:write → rate limit → per-tenant cap
 2. اعتبار شکل و قواعد payload-only (I-1..I-4, I-21, I-22، سقف asset)
 3. request_hash = SHA-256(canonical v1) در ledger-application (ADR 0017)
 4. resolve اهداف (خواندن بدون قفل): bucket → (wallet_id, asset, external_account_id, position_id);
    wallet+asset → bucket اصلی؛ system → position مستقیم (اگر نبود، در مرحلهٔ 9 ساخته می‌شود)
 5. AccountResolver روی external_account_idهای متمایز: cache؛ نبود یا کهنه → یک BatchGetAccounts gRPC
    با deadline محدود → upsert snapshot (فقط نسخهٔ بزرگ‌تر). هیچ فراخوانی شبکه بعد از BEGIN نیست.
── تراکنش DB (READ COMMITTED) ─────────────────────────────────────
 6. BEGIN; SET LOCAL lock_timeout, statement_timeout, transaction_timeout (ADR 0026);
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
   مجموع refundها سیاست Payments است ([تأیید مالک]).
5. reverse کردن reversal ممنوع است. reversal همزمان روی همان positionها سریالی می‌شود و unique index
   پشتوانهٔ نهایی است.

### 8.4 چرخهٔ عمر Wallet و Bucket (محلی در Ledger)

| عملیات | پیش‌شرط‌ها | قفل‌ها و اثر (در یک تراکنش) |
|---|---|---|
| **CreateWallet** (`owner_ref`، `wallet_type`، Bucket اصلی برای هر asset پیش‌فرض) | اگر Wallet باز برای همان `(owner_ref, wallet_type)` وجود داشته باشد، `WALLET_ALREADY_EXISTS` (ایندکس `ux_wallet_owner_open` پشتوانه است). Account هر Bucket اصلی پیش از BEGIN resolve می‌شود | ① anchor Accountها (`KEY SHARE`). INSERT در `wallets` با ACTIVE، INSERT در `wallet_status_events`، INSERT Bucketها و positionها و `bucket_status_events`. outbox با `WalletCreated`. idempotency |
| **CreateBalanceBucket** (Wallet، asset، `external_account_id`، `product_code`، `is_primary`) | Wallet باید ACTIVE باشد. Account باید باز باشد، asset را پشتیبانی کند و `can_credit` داشته باشد. Bucket اصلی تکراری → `PRIMARY_BUCKET_EXISTS`. اینکه `external_account_id` را caller بدهد یا Ledger از سیاست محصول resolve کند **NOT DECIDED** است. پیشنهاد: caller می‌دهد و Ledger اعتبارسنجی می‌کند | ① anchor Account → ② Wallet `FOR UPDATE`. INSERT Bucket، position و رویداد |
| **SuspendWallet** / **ActivateWallet** | انتقال مجاز: ACTIVE↔SUSPENDED | ② Wallet `FOR UPDATE` → ③ همهٔ positionهای Bucketهای Wallet `FOR UPDATE` (مرتب) → UPDATE `wallets.status` و INSERT رویداد |
| **SuspendBalanceBucket** / **ActivateBalanceBucket** | Wallet بسته نباشد | ② Wallet → ③ position همان Bucket → UPDATE Bucket و رویداد |
| **CloseBalanceBucket** | `posted_balance = 0`، `reserved_amount = 0` و `active_hold_count = 0`. Bucket اصلی تا وقتی Wallet باز است بسته نمی‌شود (`PRIMARY_BUCKET_NOT_CLOSABLE`، شاهد Sarv) | ② Wallet → ③ position → بررسی → UPDATE Bucket به CLOSED و رویداد |
| **CloseWallet** | همهٔ positionهای همهٔ Bucketها صفر و بدون hold، وگرنه `WALLET_BALANCE_NOT_ZERO`. `CLOSED` پایانی است | ② Wallet → ③ همهٔ positionها (مرتب) → بستن همهٔ Bucketها و Wallet. رویدادها و outbox با `WalletClosed` |

- Wallet بسته **باز نمی‌شود** (ADR 0025، تصمیم «CLOSED is terminal»). اینکه برای همان مالک Wallet جدید
  ساخته شود NOT DECIDED است و ایندکس فعلی آن را مجاز می‌کند.
- اگر Account تجمیعی (omnibus) باشد، تعلیق یک مشتری یعنی **تعلیق Wallet او در Ledger**، نه freeze
  Account. در این حالت بیشتر freezeهای سطح مشتری محلی می‌شوند.

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
| **CreateHold** (Bucket یا Wallet با asset، مبلغ، انقضا) | ① anchor Account Bucket → ③ position Bucket | Wallet و Bucket باید ACTIVE باشند و Account باید `can_debit` داشته باشد. شرط `available ≥ amount`. INSERT در `holds` و رویداد `CREATED`. `reserved += amount` و `active_hold_count += 1`. outbox و idempotency |
| **CaptureHold** (x، اهداف مقصد، final) | ① anchors همهٔ Accountها → ③ positionها → ④ hold | hold باید ACTIVE باشد و `now < expires_at` (`HOLD_EXPIRED`). شرط `x ≤ remaining` (`HOLD_AMOUNT_EXCEEDED`؛ over-capture: [تأیید مالک]). تراکنش `HOLD_CAPTURE` ساخته می‌شود: بدهکار position Bucket، بستانکار مقصد. سپس `reserved −= x` و `captured += x`. اگر final باشد، باقیمانده آزاد می‌شود. رویدادها و outbox |
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
- **Hold و تعلیق**: تعلیق Wallet یا Bucket، یا freeze Account، ساختن hold جدید را رد می‌کند. اینکه
  capture یک hold قدیمی مجاز باشد [تأیید مالک] است. پیش‌فرض پیشنهادی: رد، و آزاد شدن hold با انقضا.

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

1. **رویداد**: Account Service رویداد `AccountStateChanged` را منتشر می‌کند، با فیلدهای `account_version`
   یکنواخت صعودی، وضعیت، مجوزها، normal side، سیاست اضافه‌برداشت و asset‌ها، و کلید
   `{tenant}:{account_id}`. Ledger در یک تراکنش این کارها را می‌کند: ثبت inbox، `FOR UPDATE` روی ردیف
   cache، INSERT در `account_snapshots`، و UPDATE cache **فقط اگر نسخه بزرگ‌تر باشد**.
2. **Lookup دسته‌ای**: `BatchGetAccounts(ids[])` با یک RPC و deadline کوتاه، **خارج از تراکنش**. این
   فراخوانی در PostTransaction، CreateHold، CreateWallet و CreateBalanceBucket استفاده می‌شود. وقتی
   Account Service در دسترس نیست و cache معتبر است، با cache ادامه داده می‌شود. وقتی cache هم نیست،
   `UNAVAILABLE` برمی‌گردد. بیشینهٔ کهنگی مجاز NOT DECIDED است.
3. **Anchor**: هر عملیاتی که به Account تکیه دارد (posting، hold، ایجاد Bucket) روی ردیف cache آن قفل
   `FOR KEY SHARE` می‌گیرد. اعمال وضعیت Account با `FOR UPDATE` انجام می‌شود. این دو با هم تعارض دارند،
   پس **در Ledger** تغییر وضعیت Account با postingها خطی‌سازی می‌شود. [بنچمارک]: اگر Account تجمیعی
   (omnibus) باشد، همهٔ postingهای مشتریان قفل shared روی **یک** ردیف می‌گیرند و MultiXact hot-spot
   ایجاد می‌شود. گزینهٔ جایگزین این است که posting از `pg_advisory_xact_lock_shared` و fence از
   `pg_advisory_xact_lock` استفاده کند. قفل advisory در lock manager نگه داشته می‌شود، نه در tuple، و
   MultiXact نمی‌سازد. هزینهٔ هر دو باید اندازه‌گیری شود.
4. **Fence (`ApplyAccountState`)**: Account Service برای **freeze** یا هر محدودیت debit/credit، **پیش
   از** اعلام موفقیت به caller خود، این RPC را روی Ledger صدا می‌زند. Ledger زیر قفل ① `FOR UPDATE`
   snapshot را ثبت و cache را به‌روز می‌کند. این RPC با `account_version` idempotent است. نسخهٔ کهنه
   `ACCOUNT_STATE_STALE` می‌گیرد.
5. **Seal برای close Account**: «مانده صفر است» واقعیتی است که فقط Ledger می‌داند. پس close **باید**
   این مسیر را برود: ① `FOR UPDATE` → ③ **همهٔ positionهایی که این Account را دارند** (مستقیم و Bucket،
   مرتب). Ledger بررسی می‌کند Σ مانده صفر است و hold وجود ندارد، و همچنین **هیچ Bucket باز (غیر CLOSED)
   به این Account اشاره نمی‌کند**. اگر شرط برقرار نباشد، `ACCOUNT_BALANCE_NOT_ZERO` یا
   `ACCOUNT_HAS_OPEN_BUCKETS` برمی‌گرداند. ساختن Bucket جدید روی همان Account هم anchor ① را می‌گیرد، پس
   پس از seal دیگر ممکن نیست. [مسدود: اطلاعات]: در مدل omnibus، seal یعنی قفل میلیون‌ها position. پس
   close یک Account تجمیعی عملاً یک رویهٔ عملیاتی است و نه یک RPC. این یکی از دلایل پیشنهاد گزینهٔ (b)
   در §4.2 است.
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
  fence معتبرند» باید در قرارداد هر دو سرویس نوشته شود ([تأیید مالک]).
- **Fence ناموفق**: Account Service نباید freeze یا close را کامل اعلام کند. باید retry کند، یا وضعیت
  `PENDING_FREEZE` و `PENDING_CLOSE` را نشان دهد.
- **پیامد گزینهٔ omnibus**: freezeهای سطح مشتری باید به تعلیق Wallet در Ledger تبدیل شوند (محلی و بدون
  race). freeze سطح Account در این مدل همهٔ مشتریان را متوقف می‌کند.

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
  `payload-inspector` است و هر مشاهده در `payload_access_log` ثبت می‌شود. نگهداری NOT DECIDED است
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
| `SELECT … FOR UPDATE` مرتب | درخواست‌ها در صف می‌ایستند. throughput با latency زمان commit محدود است | قفل‌های مرتب، بدون deadlock ناشی از وارونگی ترتیب | از قفل تا commit | ADR 0020 و 0026. اندازه‌گیری ندارد |
| UPDATE شرطی اتمیک (`SET b=b+Δ WHERE b+Δ ≥ 0 RETURNING …`) | یک round trip کمتر. همچنان سریالی | باید مرتب اجرا شود | از UPDATE تا commit | Sarv برای حساب سیستمی (`ExecuteUpdate`) |
| تقسیم حساب داغ به N زیرحساب | سریالی‌شدن را N برابر کاهش می‌دهد | — | — | ADR 0026، تصمیم 4 |

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
| دامنه | `(tenant_id, idempotency_key)` روی **همهٔ** عملیات نوشتن (ADR 0017). رکورد عملیات و `client_id` را نگه می‌دارد |
| hash | SHA-256 روی encoding کانونیک نسخه‌دار از فیلدهای کسب‌وکار. محاسبه در `ledger-application` انجام می‌شود. ترتیب postingها اهمیت دارد |
| کلید یکسان و payload یکسان | ACCEPTED: منبع ثبت‌شده با وضعیت فعلی و `idempotent_replay=true`. REJECTED: همان کد خطا |
| کلید یکسان و payload، عملیات یا client متفاوت | `DUPLICATE_IDEMPOTENCY_KEY_CONFLICT` (`ALREADY_EXISTS`/409) |
| درخواست رد شده | ردهای وابسته به وضعیت یا زمان **ثبت می‌شوند** (ADR 0018، ستون Recorded). ردهای شکل یا احراز هویت یا `CONTENTION` ثبت نمی‌شوند و دوباره ارزیابی می‌شوند |
| درخواست تکراری همزمان | `pg_advisory_xact_lock(key64)` درخواست‌های هم‌کلید را در **شروع** تراکنش سریالی می‌کند. درخواست دوم پس از commit اولی رکورد را می‌بیند و replay می‌شود. برخورد hash 64 بیتی فقط انتظار بی‌مورد ایجاد می‌کند و اثری بر درستی ندارد. unique PK پشتوانهٔ نهایی است |
| نگهداری | `ACCEPTED`: تا وقتی journal هست، یعنی برای همیشه. `REJECTED`: NOT DECIDED. حداقل باید از افق retry کلاینت به‌اضافهٔ زمان حل ابهام بیشتر باشد. [مسدود: اطلاعات] |
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

- **سود و زیان FX**: سود spread در زمان معامله ثبت می‌شود، مطابق سطرهای بالا. ارزیابی مجدد موقعیت‌های
  clearing (سود و زیان تحقق‌نیافته) یک فرایند دوره‌ای است که تراکنش `ADJUSTMENT` با `approval_ref` ثبت
  می‌کند. ارز گزارش‌دهی (functional currency) و منبع نرخ مرجع **NOT DECIDED** هستند.
- **Reversal FX** همهٔ legها را با **همان مبالغ اولیه** معکوس می‌کند، نه با نرخ روز. اختلاف نرخ فقط در
  ارزیابی مجدد ظاهر می‌شود.
- quote یک‌بارمصرف است (`ux_ltx_fx_quote_once`). [تأیید مالک]

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
| `CreateWallet` | `POST /v1/wallets` | `wallets:write` | الزامی |
| `GetWallet` | `GET /v1/wallets/{wallet_id}` | `wallets:read` | — |
| `ListWallets` | `GET /v1/wallets?owner_ref=&status=` | `wallets:read` | — |
| `SuspendWallet` / `ActivateWallet` | `POST /v1/wallets/{wallet_id}/suspension`، `…/activation` | `wallets:write` | الزامی |
| `CloseWallet` | `POST /v1/wallets/{wallet_id}/closure` | `wallets:write` | الزامی |
| `CreateBalanceBucket` | `POST /v1/wallets/{wallet_id}/buckets` | `wallets:write` | الزامی |
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
| `CreateHold` | `POST /v1/holds` (هدف: Bucket یا Wallet با asset) | `holds:write` | الزامی |
| `CaptureHold` / `ReleaseHold` | `POST /v1/holds/{hold_id}/captures`، `…/releases` | `holds:write` | الزامی |
| `GetHold` / `ListHolds` | `GET /v1/holds/{hold_id}`، `GET /v1/holds?wallet_id=&bucket_id=&status=` | `holds:read` | — |
| `ExecuteFx` | `POST /v1/fx-executions` | `fx:write` | الزامی |
| Settlement | `POST /v1/settlement-batches`، `…/{id}/items`، `…/{id}/completion` | `settlement:write` | الزامی |
| Reconciliation | `/v1/reconciliation/...` (فقط REST عملیاتی) | `reconciliation:*` | الزامی برای نوشتن |
| `ApplyAccountState` (fence و seal) | — (فقط gRPC داخلی) | `accounts:fence` | با `account_version` |

**حذف می‌شوند** (فقط lifecycle Account): `CreateAccount`، `GetAccount`، `ListAccounts`، `CloseAccount`،
پیام `Account`، و scopeهای `accounts:write`، `accounts:read` و `accounts:overdraft`. **اضافه می‌شوند**:
`wallets:write` و `wallets:read`.

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
  "primaryBuckets": [ {"assetCode": "IRR", "externalAccountId": "acc_dep_cust_552_irr", "productCode": "MAIN"} ] }
```

خطا (REST): بدنه `google.rpc.Status` همراه با `LedgerErrorDetail` است، با HTTP 400 برای
`FAILED_PRECONDITION`:

```json
{"code": 9, "message": "insufficient available funds",
 "details": [{"@type": "type.googleapis.com/ledger.v1.LedgerErrorDetail",
              "code": "ERROR_CODE_INSUFFICIENT_FUNDS", "correlationId": "pay-7f3c…", "requestId": "r-…"}]}
```

### 13.3 کدهای خطا

جدول ADR 0018 حفظ می‌شود. `ACCOUNT_ALREADY_EXISTS` (19) deprecated می‌شود. `ACCOUNT_BALANCE_NOT_ZERO`
(21) **می‌ماند** و برای seal Account به کار می‌رود. کدهای جدید:

| کد | gRPC / HTTP | Recorded |
|---|---|---|
| `ACCOUNT_UNKNOWN` | NOT_FOUND / 404 | yes |
| `ACCOUNT_RESTRICTED` (freeze، یا debit/credit غیرمجاز) | FAILED_PRECONDITION / 400 | yes |
| `ACCOUNT_HAS_OPEN_BUCKETS` (seal) | FAILED_PRECONDITION / 400 | n/a (fence) |
| `ACCOUNT_STATE_STALE` | FAILED_PRECONDITION / 400 | n/a |
| `ASSET_NOT_ALLOWED` | FAILED_PRECONDITION / 400 | yes |
| `WALLET_NOT_FOUND`، `BUCKET_NOT_FOUND` | NOT_FOUND / 404 | yes |
| `WALLET_ALREADY_EXISTS` | ALREADY_EXISTS / 409 | yes |
| `WALLET_NOT_ACTIVE`، `BUCKET_NOT_ACTIVE` | FAILED_PRECONDITION / 400 | yes |
| `WALLET_BALANCE_NOT_ZERO`، `BUCKET_BALANCE_NOT_ZERO` (شامل hold فعال) | FAILED_PRECONDITION / 400 | yes |
| `PRIMARY_BUCKET_EXISTS`، `PRIMARY_BUCKET_NOT_CLOSABLE` | FAILED_PRECONDITION / 400 | yes |
| `HOLD_NOT_FOUND` | NOT_FOUND / 404 | yes |
| `HOLD_NOT_ACTIVE`، `HOLD_EXPIRED`، `HOLD_AMOUNT_EXCEEDED` | FAILED_PRECONDITION / 400 | yes |
| `FX_QUOTE_EXPIRED` | FAILED_PRECONDITION / 400 | yes |
| `FX_QUOTE_MISMATCH` | INVALID_ARGUMENT / 400 | no |
| `SETTLEMENT_BATCH_STATE_INVALID` | FAILED_PRECONDITION / 400 | yes |

### 13.4 احراز هویت، timeout، صفحه‌بندی و نسخه‌بندی

- **احراز هویت و مجوز**: mTLS به‌اضافهٔ JWT client-credentials (ADR 0023). tenant از توکن خوانده
  می‌شود. scopeها همان فهرست بالا هستند. `accounts:fence` فقط به هویت Account Service داده می‌شود.
- **Timeout**: deadline در gRPC از کلاینت به سرور منتشر می‌شود. سرور
  `min(deadline − margin, server_max)` را به `transaction_timeout` و `statement_timeout` تبدیل می‌کند.
  از نسخهٔ 17، PostgreSQL پارامتر `transaction_timeout` را دارد. این گزاره در این بازبینی تأیید نشد و در
  spike بررسی شود. **Retry** فقط با همان کلید، فقط برای `ABORTED`، `UNAVAILABLE`، `DEADLINE_EXCEEDED` و
  `RESOURCE_EXHAUSTED`، و با backoff.
- **صفحه‌بندی**: همه از نوع keyset هستند. `ListBucketPostings` و `ListAccountPostings` بر اساس `position_seq` هستند (ADR 0014). `ListWalletHistory` بر اساس `(posted_at, posting_id)` است و بی‌فاصله نیست.
  بقیه بر اساس `(created_at, id)` با UUIDv7. اندازهٔ صفحه 1 تا 1000 است. page token opaque است و امضا یا
  اعتبارسنجی می‌شود.
- **نسخه‌بندی**: تا `v1.0.0` قرارداد پیش‌نویس است (ADR 0032). رویدادها در بستهٔ جداگانهٔ
  `ledger.events.v1` هستند.

---

## 14. Kafka and CQRS

### 14.1 رویدادها

| Topic | کلید پیام | رویدادها | ترتیب |
|---|---|---|---|
| `ledger.transactions.v1` | `{tenant_id}:{transaction_id}` | `TransactionPosted`، `TransactionReversed`، `FxExecuted` | هر رویداد مستقل است. ترتیب سراسری **ندارد** (ADR 0014) |
| `ledger.wallets.v1` | `{tenant_id}:{wallet_id}` | `WalletCreated`، `WalletStatusChanged`، `WalletClosed`، `BucketCreated`، `BucketStatusChanged` | برای هر Wallet مرتب است، چون lifecycle زیر قفل Wallet سریالی است |
| `ledger.position-activity.v1` (اختیاری، [تأیید مالک]) | `{tenant_id}:{position_id}` | `PositionActivity{wallet_id?, bucket_id?, external_account_id, asset, from_seq, to_seq, balance_after, available_after}` | برای هر position مرتب است. مصرف‌کننده با `position_seq` فاصله‌ها را تشخیص می‌دهد |
| `ledger.holds.v1` | `{tenant_id}:{hold_id}` | `HoldCreated`، `HoldCaptured`، `HoldReleased`، `HoldExpired` | برای هر hold مرتب است |
| `ledger.settlement.v1` | `{tenant_id}:{batch_id}` | `SettlementBatchSubmitted`، `SettlementCompleted`، `SettlementFailed` | برای هر batch مرتب است |
| `ledger.reconciliation.v1` | `{tenant_id}:{run_id}` | `ReconciliationCompleted`، `DiscrepancyDetected` | برای هر run مرتب است |
| مصرف می‌شود: `accounts.lifecycle.v1` (مالک: Account Service) | `{tenant}:{account_id}` | `AccountStateChanged` | برای هر حساب مرتب است. `account_version` هم جلوی کهنگی را می‌گیرد |

**Envelope** (protobuf در `contracts/proto/ledger/events/v1`): `event_id` (UUIDv7)، `event_type`،
`schema_version`، `tenant_id`، `occurred_at`، `producer`، `correlation_id`، `causation_id`
(کلید idempotency)، `trace_parent`، `payload`. **PII ممنوع است.** رجیستری schema و مجوز آن NOT DECIDED
است. مجوز رجیستری‌ها متفاوت است و پیش از انتخاب بررسی شود.

### 14.2 تضمین‌ها

- **[توجیه فنی]** هیچ ادعای exactly-once بین PostgreSQL و Kafka وجود ندارد. outbox در همان تراکنش نوشته
  می‌شود. relay **حداقل یک بار** منتشر می‌کند. تنظیمات producer: `acks=all` و
  `enable.idempotence=true`. idempotence در producer فقط تکرار درون یک session را حذف می‌کند و به
  outbox ربطی ندارد. relay پس از crash ممکن است پیام را دوباره بفرستد.
- **ترتیب برای هر کلید**: رویدادهایی که کلید یکسان دارند (یک hold یا یک حساب) را تراکنش‌هایی می‌سازند
  که با **قفل همان ردیف** سریالی شده‌اند. پس ترتیب `outbox_id` آن‌ها با ترتیب commit یکی است. relay
  **واحد و فعال** است: leader با advisory lock انتخاب می‌شود، ردیف‌های منتشرنشده را به ترتیب
  `outbox_id` می‌خواند و به ترتیب می‌فرستد. relay چندتایی با `SKIP LOCKED` ترتیب هر کلید را می‌شکند و
  رد می‌شود. مشکل ADR 0014، یعنی commit خارج از ترتیب، فقط بین کلیدهای **متفاوت** رخ می‌دهد. چون relay
  بر اساس `published_at IS NULL` کار می‌کند و cursor روی id ندارد، ردیفی که دیر commit شده در poll بعدی
  برداشته می‌شود و گم نمی‌شود.
- **مصرف‌کننده**: inbox با `(consumer_name, message_id)` در همان تراکنشی که اثر را می‌نویسد. offset پس از
  commit ذخیره می‌شود. وقتی فاصله‌ای در `position_seq` دیده شود، مصرف‌کننده از `ListBucketPostings` یا `ListAccountPostings`
  دوباره همگام می‌شود (resync). برای بازپخش (replay)، offset عقب برده می‌شود و inbox تکرارها را حذف
  می‌کند.
- **تنظیمات Kafka** مثل replication factor، `min.insync.replicas` و retention، و همچنین DLQ: NOT DECIDED.
  نقطهٔ شروع رایج RF=3 و min.insync=2 است، ولی باید با سطح دسترس‌پذیری هدف تأیید شود.

### 14.3 CQRS

- نوشتن از port تراکنش (ADR 0030) می‌گذرد. خواندن از read port بدون قفل.
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
| `ledger_relay` | outbox: `SELECT`، `UPDATE(published_at, publish_attempts, last_error)`، `DELETE` | relay |
| `ledger_readonly` | `SELECT` روی همه. یک policy با `USING (true)` | reconciliation، audit، گزارش |
| `ledger_operator` | registry مستأجرها | رویه‌های دستی تأییدشده |

- **تغییرناپذیری در سطح DB**: سه لایه دارد. ① نداشتن grant. ② trigger با `RAISE` روی UPDATE، DELETE و
  TRUNCATE که حتی مالک را متوقف می‌کند، مگر trigger عمداً غیرفعال شود. ③ audit روی DDL و ALTER و
  تغییر نقش‌ها، مثلاً با pgaudit (مجوز و نسخه unverified). superuser همهٔ این‌ها را دور می‌زند، پس
  دسترسی superuser باید از نوع break-glass و با ثبت باشد. این لایه از Oracle Immutable Tables در ADR 0007
  و 0028 **ضعیف‌تر** است. مالک این را بپذیرد ([تأیید مالک]). کشف دستکاری با بررسی زنجیرهٔ `position_seq`
  و `balance_after` و I-4 در reconciliation انجام می‌شود.
- **RLS** [توجیه فنی، هزینه: بنچمارک]: در ADR 0027 به دلیل ابهام در edition Oracle به تعویق افتاده بود.
  در PostgreSQL محدودیت edition وجود ندارد. پس RLS لایهٔ **دوم** است و جایگزین predicate صریح نیست.
  `set_config('app.tenant_id', …, true)` در شروع هر تراکنش صدا زده می‌شود و فقط در همان تراکنش معتبر
  است، پس با connection pool امن است. اگر تنظیم نشده باشد، هیچ ردیفی برگردانده نمی‌شود (fail closed).
  تست‌ها باید نشان دهند که نبود تنظیم صفر ردیف یا خطا برمی‌گرداند.
- **Secrets** فقط از Vault یا env می‌آیند. اتصال DB با TLS است. توصیه: credential کوتاه‌عمر از Vault
  (database secrets engine). مجوز و دسترس‌پذیری Vault [تأیید مالک] است.
- **لاگ**: بدنهٔ درخواست، `external_ref` و `external_account_id` ممکن است مرتبط با PII باشند (ADR
  0011). `external_account_id` باید opaque باشد و Account Service نباید شمارهٔ حساب واقعی یا IBAN را
  به‌عنوان شناسه بفرستد. این یک **الزام قرارداد** برای Account Service است ([تأیید مالک]).
- **checklist** (`docs/security/checklist.md`): بندهای A4، B3، C1 و D6 باید بازنویسی شوند (§19.2).

---

## 16. Operational Requirements

### 16.1 داده‌هایی که در دسترس نیستند (NOT DECIDED) و آنچه باید اندازه‌گیری شود

| مورد | وضعیت | اندازه‌گیری یا تصمیم لازم |
|---|---|---|
| TPS میانگین و اوج، تعداد posting در هر تراکنش | NOT DECIDED | از تولید Sarv: شمار `Transactions` و `LedgerEntries` در ساعت اوج. با harness بار Sarv و Kimia اندازه‌گیری شود |
| نرخ برخورد روی حساب داغ (fee، clearing) | NOT DECIDED | سهم تراکنش‌هایی که حساب fee را لمس می‌کنند، و p99 زمان commit |
| حجم و رشد داده | NOT DECIDED | اندازهٔ هر posting و ایندکس در PG18 با داده‌های نمونه |
| نگهداری قانونی journal، payload و idempotency | NOT DECIDED | واحد حقوقی، DPO و الزامات ناظر |
| RPO و RTO | NOT DECIDED | مالک تعیین می‌کند. RPO=0 به replica همگام نیاز دارد (`synchronous_standby_names`) که latency را افزایش می‌دهد [بنچمارک] |
| تعداد tenant | NOT DECIDED | Sarv تک‌tenant است |
| SLO دسترس‌پذیری | NOT DECIDED | — |
| کهنگی مجاز cache حساب و lag مجاز رویداد | NOT DECIDED | Account Service و Ledger با هم تعیین می‌کنند |
| مقادیر timeout (ADR 0026) | NOT DECIDED | پس از بنچمارک |

### 16.2 پشتیبان‌گیری، بازیابی و تکثیر

- WAL archiving و PITR با ابزاری مثل pgBackRest (مجوز و نسخه unverified). بازیابی آزمایشی دوره‌ای و
  اندازه‌گیری RTO واقعی.
- streaming replication و failover خودکار (ابزار NOT DECIDED). پس از failover، کلاینت‌ها با همان کلید
  retry می‌کنند. تراکنش‌هایی که در پنجرهٔ RPO از دست رفته‌اند با همان کلید دوباره ثبت می‌شوند و
  idempotency این را امن می‌کند (ADR 0029، تصمیم 5).
- Kafka: اگر رویدادی پس از بازیابی DB ارسال شده باشد ولی تراکنش آن از دست رفته باشد، رویداد بدون پشتوانه
  می‌ماند. مصرف‌کنندگان باید با reconciliation رویداد در برابر API این را تشخیص دهند. این ریسک فقط وقتی
  RPO>0 باشد وجود دارد.

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
4. **ردیف قدیم projection در یک جدول audit کپی می‌شود.**
5. با نقش جداگانه‌ای که UPDATE دارد و همراه `approval_ref`، UPDATE انجام می‌شود.
6. اگر خود journal اشتباه باشد، projection دست نمی‌خورد و یک تراکنش `ADJUSTMENT` ثبت می‌شود.

این، تصمیم «Nothing is rebuilt» در ADR 0029 را بازنگری می‌کند.

### 16.4 پایش و هشدار

| شاخص | هشدار |
|---|---|
| p50/p95/p99 نوشتن، و زمان انتظار برای قفل | بالاتر از هدف (NOT DECIDED) |
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
  جدول جدید به‌اضافهٔ view ساخته می‌شود (همان روح ADR 0028، تصمیم 5).
- مسئولیت on-call: مالک Ledger به drift، outbox و DB پاسخ می‌دهد. Payments به UNKNOWN و provider پاسخ
  می‌دهد. Account Service به fence و lag پاسخ می‌دهد. ساختار تیم و شیفت NOT DECIDED است.

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
**تصمیم: [تأیید مالک]**. این سند برای هر سه گزینه یک طرح دامنه و DB ارائه می‌کند که به زبان وابسته
نیست.

---

## 18. Migration Strategy

اصل حاکم این است که **در هر لحظه، برای هر جریان مالی (و هر حساب) دقیقاً یک سیستم مرجع ثبت (system of
record) وجود دارد**. ثبت دوگانه (dual-write) از کد کاربر وجود نخواهد داشت.

| فاز | کار | خروجی و معیار پیشرفت |
|---|---|---|
| 0 | پذیرش ADRها، اصلاح `CLAUDE.md` و proto، spike روی PG18، jOOQ و Kafka | ADRها Accepted. spike سبز |
| 1 | Kimia در حالت **shadow**: Sarv مرجع است. رویدادهای `TransactionPosted` و `TransactionReversed` در Sarv، که امروز با Wolverine و صف محلی منتشر می‌شوند، به Kafka پل زده می‌شوند و Kimia آن‌ها را با کلید `sarv:{OperationType}:{ReferenceId}` import می‌کند | هر روز مانده‌ها در دو سیستم مقایسه می‌شوند و drift صفر است. **کلید باید OperationType را شامل شود**، چون یکتایی در Sarv روی `(ReferenceId, OperationType)` است |
| 2 | Account Service: استخراج `LedgerAccount` و `SystemAccount` از Ledger در Sarv به Account Service، و رویداد `AccountStateChanged`. **Wallet و BalanceBucket در Ledger می‌مانند** و به `wallets` و `balance_buckets` در Kimia منتقل می‌شوند، با همان شناسه‌ها | Kimia cache را پر می‌کند. هر Bucket در Sarv (که 1:1 با یک `LedgerAccount` است) یک `external_account_id` می‌گیرد، طبق تصمیم §4.2. مانده‌ها از `BalanceBucket.TotalBalance` و `LockedBalance` و `LedgerAccount.BalanceMinorUnits` به positionها نگاشت می‌شوند |
| 3 | **مانده‌های افتتاحیه**: در نقطهٔ cut-over برای هر position (Bucket یا حساب سیستمی) یک تراکنش `OPENING_BALANCE` در مقابل حساب انتقال (migration suspense) ثبت می‌شود. یا تاریخچهٔ کامل replay می‌شود ([تأیید مالک]) | مجموع حساب انتقال صفر است |
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

| ADR | حکم | دلیل و شاهد | پیامد |
|---|---|---|---|
| 0014 per-account sequence | **REVISE** | ایدهٔ اصلی درست است: commit خارج از ترتیب، ترتیب سراسری را غیرممکن می‌کند. کلید از `account_id` به **`position_id`** تغییر می‌کند (Bucket، یا position مستقیم Account+asset). `as_of` برای position خالی برابر `created_at` همان Bucket یا position است | `position_seq` و `balance_after` روی posting |
| 0015 REST conforms to transcoding | **ACCEPT** | استدلال به پایگاه داده وابسته نیست. حذف 422 درست است | مسیرهای §13.1 |
| 0016 entry model | **REVISE** | `effective_at`، reversal ساخته‌شده در سرور، حساب تکراری و `PostingInput` حفظ می‌شوند. هدف posting به صورت `oneof` درمی‌آید (Bucket، Wallet اصلی، Account مستقیم). جدول پیوند reversal با partial unique index جایگزین می‌شود. `REFUND` و `ADJUSTMENT` اضافه می‌شوند. «Account بسته» شامل Wallet و Bucket بسته هم می‌شود | `Entry` به `Transaction` تغییر نام می‌دهد |
| 0017 idempotency semantics | **REVISE** | معنا درست است. abort تراکنش در PostgreSQL، advisory lock، نگهداری `REJECTED`، و اضافه شدن عملیات Wallet، Bucket، hold و fence به فضای کلید | §11.2 |
| 0018 error contract | **REVISE** | نگاشت وضعیت‌ها و header درست است. کدهای Wallet، Bucket، hold، FX، Account و asset اضافه می‌شوند | §13.3 |
| 0019 money range | **REVISE** | int64 به دامنهٔ asset‌ها وابسته است و [مسدود: اطلاعات] است. exponent برای هر حساب به scale تغییرناپذیر برای هر `asset_code` تغییر می‌کند. FX با snapshot quote و بررسی آن | §7.2، §12.1 |
| 0020 insert-only balances | **REJECT** | hold و موجودی reserved و available بدون projection قابل‌تغییر ممکن نیست. `FOR UPDATE` در PostgreSQL به UPDATE نیاز دارد. بخش‌های **حفظ‌شده**: `balance_after` روی posting، comparator قفل، journal بدون UPDATE | ADR تازه: positionها و زنجیرهٔ تغییرناپذیر |
| 0021 no events in v1 | **REJECT** | مصرف‌کنندگان در baseline نام برده شده‌اند. قید چهارم آن **حفظ می‌شود** | §14 |
| 0022 no holds in v1 | **REJECT** | hold بومی روی Bucket در baseline آمده است | §8.5 |
| 0023 service edge | **ACCEPT** | registry، allow-list و fail-closed درست‌اند | scopeهای `wallets:*` و `accounts:fence` |
| 0024 overdraft scope | **REVISE** | Ledger Account نمی‌سازد. سیاست و سقف اضافه‌برداشت از Account Service می‌آید (snapshot). اصل «منبع پول ساختن کاری با امتیاز ویژه است» به Account Service منتقل می‌شود. **قاعدهٔ سطح Wallet** (Bucketها به‌طور پیش‌فرض منفی نمی‌شوند) در Ledger می‌ماند. ریسک «هر کلاینت با `transactions:write` روی هر هدف tenant می‌نویسد» باقی است | [تأیید مالک]: حق posting برای هر Wallet، Account یا کلاس |
| 0025 account lifecycle and lookup | **REVISE** (تقسیم) | **در Ledger می‌ماند، ولی برای Wallet و Bucket**: ایجاد با idempotency، lookup و فهرست (`ListWallets` با `owner_ref`، که جای `ListAccounts` با `external_ref` را می‌گیرد)، بستن فقط با موجودی صفر و بدون hold، `CLOSED` پایانی، نبود `metadata` (`owner_ref` opaque است)، و ویژگی‌های هویتی تغییرناپذیر (trigger). **به Account Service منتقل می‌شود**: ایجاد Account، یکتایی `external_ref` Account، نوع، ارز، `allow_negative`، و تغییرناپذیری آن‌ها. «وضعیت به صورت insert-only روی ردیف‌های closure» با ستون وضعیت به‌اضافهٔ `*_status_events` تغییرناپذیر جایگزین می‌شود | §8.4، §13.1 |
| 0026 timeouts | **ACCEPT** | سیاست درست است. سازوکارها برای PostgreSQL بازنویسی شوند (`lock_timeout`، `statement_timeout`، `transaction_timeout` (unverified)، `idle_in_transaction_session_timeout`) | اعداد NOT DECIDED |
| 0027 tenant isolation | **REVISE** | FKهای composite حفظ می‌شوند. RLS به‌عنوان لایهٔ دوم (بدون محدودیت edition) [بنچمارک] | §7.3، بخش 14 |
| 0028 Oracle only | **REJECT** | baseline PostgreSQL 18 است. مشکل مجوز درایور Oracle حذف می‌شود و jOOQ OSS مجاز است. **پیش‌شرط**: مالک تأیید کند Oracle دیگر الزام نیست | Immutable Tables از دست می‌رود. جایگزین: grant، trigger و audit |
| 0029 reconciliation/freeze/recovery | **REVISE** | «هیچ چیز بازسازی نمی‌شود» با runbook §16.3 جایگزین می‌شود. freeze دو نوع می‌شود: **محلی** (تعلیق Wallet یا Bucket، با API و scope `wallets:write`) و **بیرونی** (Account، با fence). freeze ناشی از حادثهٔ داخلی Ledger با تعلیق Wallet یا Bucket و `reason_code` انجام می‌شود | §8.4، §9، §16 |
| 0030 transaction port | **ACCEPT** | متدها به‌روز می‌شوند: `lockAccountAnchors`، `lockWallet`، `lockPositions`، `lockHolds`، `appendTransaction`، `updatePosition`، `updateWalletStatus`، `appendOutbox`، `recordOutcome` | — |
| 0031 ledger-contracts | **ACCEPT** | `ledger.events.v1` و `WalletService` اضافه می‌شوند | — |
| 0032 contract freeze | **ACCEPT** | با تغییرات بزرگ قرارداد، این ADR حیاتی‌تر از قبل است | — |

جمع: ACCEPT 6 (0015، 0023، 0026، 0030، 0031، 0032)، REVISE 9 (0014، 0016، 0017، 0018، 0019، 0024،
0025، 0027، 0029)، REJECT 4 (0020، 0021، 0022، 0028)، DEFER 0. **تغییر نسبت به پیش‌نویس قبلی این
سند**: 0025 از REJECT به REVISE تغییر کرد. ADRهای پذیرفته‌شده‌ای که اثر می‌گیرند: 0002 باید Superseded
شود. 0007، 0008، 0010، 0011 (`owner_ref` opaque) و 0013 amend می‌شوند. **هیچ تغییری در وضعیت ADRها
اعمال نشده است.**

### 19.2 تغییرات لازم در `CLAUDE.md` و قرارداد

**`CLAUDE.md`**

| متن فعلی | تغییر |
|---|---|
| «Java 25, Spring Boot, Gradle (Kotlin DSL), Oracle» | → PostgreSQL 18 |
| جدول ماژول‌ها: `ledger-persistence-oracle`، «No jOOQ (commercial with Oracle)» | → `ledger-persistence-postgres`. «jOOQ OSS (Apache-2.0) مجاز است، فقط در persistence». ماژول‌های `ledger-contracts`، `ledger-messaging-kafka` و `ledger-account-client` اضافه می‌شوند |
| «Account types: ASSET, …, each with a normal balance side» | → طبقه‌بندی از Account Service می‌آید و Ledger آن را snapshot می‌کند |
| «Money = integer minor units (`long` / `NUMBER(38,0)`)» | → `NUMERIC(38,0)` یا `bigint` (§7.2). روی wire به صورت string |
| «Never … `BigDecimal` with scale ≠ 0» | → «for **money amounts**». نرخ FX با `BigDecimal` و `MathContext` صریح فقط در `core.fx` مجاز است |
| «Plan for Oracle Immutable / Blockchain tables» | → grant، trigger و audit (§15) |
| Scopes: `accounts:write`, `accounts:read`, `entries:*` | → فهرست §13.1 (از جمله `wallets:write` و `wallets:read`) |
| (تازه) | جدول مالکیت §1.1 اضافه شود: Wallet و BalanceBucket در Ledger، Account بیرونی. «Ledger جدول مرجع Account ندارد. Wallet و Bucket جدول‌های مرجع Ledger هستند و DELETE روی آن‌ها ممنوع است» |
| Write path: «lock accounts … update balances → insert outbox row» | به‌جز ترتیب anchor و projection و idempotency، **همین می‌ماند**. ADRهای 0020 و 0021 با آن در تعارض بودند |
| Commands: `Oracle image: gvenzl/oracle-free` | → `postgres:18` (tag دقیق و digest پین شوند) |
| Forbidden: «no jOOQ»، «SQL outside `ledger-persistence-oracle`» | → «SQL و jOOQ بیرون از `ledger-persistence-postgres` ممنوع است» |
| فهرست مجوزهای مجاز | بدون تغییر. jOOQ OSS، pgJDBC، kafka-clients و Spring Kafka در این فهرست می‌گنجند. مجوز pgJDBC در این بازبینی دوباره بررسی نشد |

**`ArchitectureTest`**: `oracle..` در قاعده‌ها با `org.postgresql..` و `org.jooq..` جایگزین شود و قاعدهٔ
Kafka اضافه شود. این **سخت‌تر کردن** قاعده است و آن را loosen نمی‌کند.

**قرارداد (`contracts/proto/ledger/v1`)**:

- `account.proto` حذف می‌شود.
- `Posting.account_id` با یک `target` از نوع `oneof` (`bucket_id`، `wallet_primary`، `external_account_id`) و `Money.asset_code` جایگزین می‌شود. `Posting` خروجی، `position_id`، `bucket_id`، `wallet_id` و `external_account_id` را دارد.
- `WalletService` (`wallet.proto`) اضافه می‌شود (§13.1).
- `amount_minor` از `int64` به `string` تغییر می‌کند، اگر §7.2 به NUMERIC برسد.
- `sequence_no` و `correlation_id` حذف می‌شوند و `position_seq`/`account_version` اضافه می‌شوند.
- `Entry` به `Transaction` تغییر نام می‌دهد.
- RPCهای hold، FX، settlement، reconciliation و fence اضافه می‌شوند.
- `ErrorCode` طبق §13.3 به‌روز می‌شود.
- بستهٔ `ledger.events.v1` اضافه می‌شود و OpenAPI دوباره تولید می‌شود.

همهٔ این‌ها پیش از `v1.0.0` مجاز است (ADR 0032).

**مستندات**: در `invariants.md` این بندها بازنویسی می‌شوند: I-6، I-7، I-10، I-11، I-13، I-16، I-23 تا
I-25، I-28 و I-30. بندهای تازه برای hold (`reserved` = Σ باقیمانده)، FX، seal و نسخهٔ حساب اضافه
می‌شوند. در `threat-model.md`، مرز broker و Account Service اضافه می‌شود، و همچنین تهدید «UPDATE روی
projection بدون posting» با کنترل §16.3. در `checklist.md` بندهای A4، B3، C1 و D6 بازنویسی می‌شوند.

---

## 20. Open Decisions and Risks

### 20.1 تصمیم‌های مالک (به ترتیب اولویت)

1. **پایگاه داده**: تأیید اینکه Oracle دیگر الزام استقرار نیست. بدون آن، ADR 0028 نمی‌تواند رد شود.
2. **نگاشت Bucket به Account** (§4.2): (a) 1:1، (b) N:1 برای هر مشتری (پیشنهاد)، یا (c) omnibus. این
   انتخاب بر seal، hot-spot روی anchor، و اینکه freeze مشتری محلی یا بیرونی باشد، اثر می‌گذارد.
3. **فهرست asset‌ها و scale** (`bigint` یا `NUMERIC`). تغییر بعدی یعنی بازنویسی journal.
4. **قرارداد Account Service**: رویداد، `account_version`، fence همگام برای freeze، و seal برای close.
5. **قواعد Wallet**: تعداد Wallet برای هر مالک، پذیرش واریز در حالت SUSPENDED، کاتالوگ `product_code`،
   اینکه `external_account_id` یک Bucket را caller بدهد یا از سیاست resolve شود، و اضافه‌برداشت روی Bucket.
6. **Java یا .NET** (§17).
7. **Payment Timeline**: مالکیت در Payments، نگهداری payload، و تأیید DPO.
8. **RPO و RTO**، نگهداری قانونی، و اهداف TPS و latency.
9. سیاست‌های hold: over-capture، capture پس از تعلیق یا freeze، و پیش‌فرض انقضا.
10. FX: ارز گزارش‌دهی، منبع نرخ مرجع، و یک‌بارمصرف بودن quote.
11. jOOQ (با قید «latest versions») یا `JdbcClient`.
12. حق posting برای هر Wallet یا Account یا کلاس (ریسک باقیماندهٔ ADR 0024)، topic اختیاری activity، و
    رجیستری schema.

### 20.2 نیازمند بنچمارک

- هزینهٔ trigger I-4 در commit
- MultiXact ناشی از `FOR KEY SHARE` روی حساب داغ
- `NUMERIC` در برابر `bigint`
- RLS
- قفل جداگانه برای هر statement در برابر یک statement
- اثر UPDATE دیرهنگام برای حساب fee
- replica همگام
- throughput relay

### 20.3 ریسک‌ها

| ریسک | شدت | کاهش |
|---|---|---|
| race بین freeze Account بیرونی و posting (§9.3). تعلیق Wallet یا Bucket محلی است و race ندارد | بالا (قانونی) | fence همگام و قرارداد صریح. freeze سطح مشتری از راه تعلیق Wallet |
| Account تجمیعی (omnibus): hot-spot روی anchor، و seal غیرعملی | بالا اگر (c) انتخاب شود | گزینهٔ (b) در §4.2، یا advisory lock مشترک [بنچمارک] |
| از دست رفتن Immutable Tables در Oracle | متوسط | trigger، audit و بررسی زنجیره. superuser با روش break-glass |
| بازطراحی بزرگ قرارداد پیش از وجود هیچ کد | متوسط | spike عمودی (PostTransaction از ابتدا تا انتها) پیش از نهایی کردن همهٔ RPCها |
| ناسازگاری فعلی مستندات (CLAUDE.md در برابر ADR 0020/0021) | متوسط | ثبت تصمیم‌ها پیش از شروع کد |
| گره خوردن jOOQ OSS به «latest PostgreSQL» | کم تا متوسط | سیاست ارتقای PG، یا `JdbcClient` |
| نبود عدد بار | بالا | بنچمارک پیش از تعیین ظرفیت (ADR 0026) |

---

## 21. Final Recommendation

1. **تصمیم‌های 1 تا 4 در §20.1 را پیش از هر کد بگیرید.** هیچ‌کدام را نمی‌توان بعداً بی‌هزینه عوض کرد.
2. ADRهای تازه (Proposed) به این ترتیب نوشته شوند:
   - PostgreSQL، jOOQ و نقش‌های DB (جایگزین 0002 و 0028)
   - مرز Account و fence و seal، و مدل Wallet/Bucket/position (اصلاح 0025 و 0024)
   - projection تراکنشی با زنجیرهٔ تغییرناپذیر (جایگزین 0020)
   - hold بومی (جایگزین 0022)
   - outbox و inbox روی Kafka (جایگزین 0021)
   - FX و settlement و reconciliation (اصلاح 0019 و 0029)
   - مرز Payment Timeline (برای Payments)

   ADRهای 0015، 0023، 0026، 0030، 0031 و 0032 با اصلاحات جزئی برای **پذیرش** به مالک پیشنهاد شوند.
3. **Spike عمودی** شامل این موارد: PostgreSQL 18 و Flyway (نسخه‌های BOM)، DDL §7.3 برای جدول‌های journal
   و projection و idempotency و outbox، `PostTransaction` با قفل مرتب، trigger I-4، RLS و تست‌های
   همروندی (کلید تکراری، A↔B، reversal همزمان)، و یک relay ساده به Kafka. همچنین harness بار با
   سناریوهای `distributed`، `hot` و `fee`، با الگوبرداری از Sarv. **اعداد ظرفیت فقط پس از این spike
   تعیین شوند.**
4. سپس hold، fence حساب، FX و settlement به همین ترتیب پیاده شوند. پس از آن مهاجرت در حالت shadow از
   Sarv (§18، فاز 1) شروع شود.
5. تا آن زمان، Ledger در Sarv همچنان مرجع می‌ماند. درس‌های Sarv در تست‌های Kimia بازتولید شوند: retry با
   jitter، audit تشخیصی و پنجره‌ای، و حالت `NeedsReconciliation`. ضعف‌های Sarv در طراحی Kimia برطرف
   شده‌اند: نبود مقایسهٔ payload، cascade delete، capture کامل، و قفل کلی موجودی.

**آنچه این سند ادعا نمی‌کند**: تنها چیزهایی که اجرا شدند این‌ها هستند: DDL §7.3 همراه یک smoke test
کارکردی روی PostgreSQL 18.7، و یک `./gradlew test` که compile آن موفق بود ولی اجرای تست‌هایش بی‌نتیجه ماند
(§2.2). هیچ تست همروندی، بار، Flyway، jOOQ یا Kafka اجرا نشده است. مسیر نوشتن، lifecycle Wallet، hold،
fence، seal و relay فقط طراحی هستند. نسخه‌هایی که با برچسب «unverified» آمده‌اند تأیید نشده‌اند.

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
