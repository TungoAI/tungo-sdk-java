# Tungo AI Java SDK (`kz.tungo:tungo-sdk`)

Официальный Java SDK для публичного API платформы **Tungo AI** (Java 17+).

## Быстрый старт

```java
import kz.tungo.sdk.TungoClient;
import kz.tungo.sdk.TungoMoney;

var client = new TungoClient("tg_live_...", "https://api.tungo.ai", "ws_...", "co_...");
var reply = client.ask("Покажи дебиторку за октябрь", null);
System.out.println(TungoMoney.formatTiyn(TungoMoney.toTiyn(45000), "KZT"));
```

## Полный справочник ресурсов и методов

<!-- sdkgen:methods -->
Автоматически сгенерировано из OpenAPI-контракта Tungo Platform API: **13 ресурсов, 82 метода, 22 вебхука**.

| Ресурс | Описание | Методы |
| --- | --- | --- |
| `client.workspaces()` | Организации (workspace), доступные ключу API | `list` |
| `client.companies()` | Компании организации и их карточки | `list` · `get` |
| `client.platform()` | Баланс платформы, книга движений, отчёты о расходе, лимиты, аудит и тарифы моделей | `listUsageRates` · `getAccount` · `getAudit / getAuditAll` · `getLedger / getLedgerAll` · `listLimits` · `listRequests / listRequestsAll` · `getUsage` |
| `client.apiKeys()` | Сведения о текущем ключе API и статистика его расхода | `getCurrent` · `getCurrentUsage` · `getUsage` |
| `client.webhooks()` | Исходящие вебхуки организации (Svix): адреса, каталог событий, доставки и проверка подписи | `listEndpoints / listEndpointsAll` · `createEndpoint` · `getEndpoint` · `updateEndpoint` · `deleteEndpoint` · `listEndpointAttempts / listEndpointAttemptsAll` · `rotateEndpointSecret` · `testEndpoint` · `listEventTypes` · `listMessages / listMessagesAll` · `listMessageAttempts / listMessageAttemptsAll` · `resendMessage` · `getUsage` |
| `client.chat()` | Диалоги с ИИ-бухгалтером: вопросы, потоковый чат (SSE), подсчёт токенов и история | `list / listAll` · `create / createStream` · `countTokens` · `get` · `update` · `delete` · `answer / answerStream` · `listEvents` · `stop` |
| `client.proposals()` | Предложения документов ИИ-бухгалтера: просмотр, одобрение записи в 1С и отклонение | `list / listAll` · `approveBatch` · `get` · `approve` · `reject` |
| `client.feed()` | Лента событий и предложений компании (страницы и живой поток SSE) | `get / getAll` · `listEvents` |
| `client.batches()` | Пачки обработки банковских выписок, входящих ЭСФ и DirectBank | `list / listAll` · `createBankStatement` · `createEsfPurchases` · `get` · `listEvents` · `stop` · `getSummary` · `listStatements / listStatementsAll` · `pollDirectbank` |
| `client.esf()` | Входящие и исходящие электронные счета-фактуры (ИС ЭСФ) | `getIncoming / getIncomingAll` · `getOutgoing / getOutgoingAll` |
| `client.forms()` | Налоговые формы (ФНО 100.00 и др.): сборка, проверка ИСНА и разрывы данных | `list` · `build` · `listDataGaps` · `getIsna` · `listNotes` |
| `client.onec()` | Состояние подключения компании к базе 1С | `getStatus` |
| `client.automations()` | Автоматизации компании: входящие хуки, сценарии, цепочки шагов, запуски и уровень автономности ИИ | `postHook` · `listHooks` · `createHook` · `getHook` · `updateHook` · `deleteHook` · `rotateHook` · `testHook` · `listRuns / listRunsAll` · `getRun` · `cancelRun` · `retryRun` · `listScenarios` · `createScenario` · `getScenario` · `putScenario` · `updateScenario` · `deleteScenario` · `runScenario` · `getState` · `putState` · `getAutonomy` · `putAutonomy` |
<!-- /sdkgen:methods -->
