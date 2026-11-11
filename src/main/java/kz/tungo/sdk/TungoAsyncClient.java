package kz.tungo.sdk;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import kz.tungo.sdk.internal.HttpEngine;
import kz.tungo.sdk.models.AutomationHookAccepted;
import kz.tungo.sdk.models.AutomationRunDetail;
import kz.tungo.sdk.models.AutomationScenarioRunInput;
import kz.tungo.sdk.models.BatchJob;
import kz.tungo.sdk.models.ChatTurnResult;
import kz.tungo.sdk.models.WebhookEvent;
import kz.tungo.sdk.resources.WorkspacesResourceAsync;
import kz.tungo.sdk.resources.CompaniesResourceAsync;
import kz.tungo.sdk.resources.PlatformResourceAsync;
import kz.tungo.sdk.resources.ApiKeysResourceAsync;
import kz.tungo.sdk.resources.WebhooksResourceAsync;
import kz.tungo.sdk.resources.ChatResourceAsync;
import kz.tungo.sdk.resources.ProposalsResourceAsync;
import kz.tungo.sdk.resources.FeedResourceAsync;
import kz.tungo.sdk.resources.BatchesResourceAsync;
import kz.tungo.sdk.resources.EsfResourceAsync;
import kz.tungo.sdk.resources.FormsResourceAsync;
import kz.tungo.sdk.resources.OnecResourceAsync;
import kz.tungo.sdk.resources.AutomationsResourceAsync;

/**
 * The asynchronous view of a {@link TungoClient}: every method returns a
 * {@link CompletableFuture}.
 *
 * <p>Calls run on the client's async executor (a pool of daemon threads unless one was supplied
 * with {@link TungoClient.Builder#asyncExecutor}). Cancelling a returned future interrupts the
 * call: the request is aborted and no retry follows. A failed call completes the future
 * exceptionally with the same exception types the sync client throws. Obtain it with
 * {@link TungoClient#async()}; it is closed together with that client.
 */
public final class TungoAsyncClient {
    private final TungoClient sync;
    private final HttpEngine engine;
    private final WorkspacesResourceAsync workspaces;
    private final CompaniesResourceAsync companies;
    private final PlatformResourceAsync platform;
    private final ApiKeysResourceAsync apiKeys;
    private final WebhooksResourceAsync webhooks;
    private final ChatResourceAsync chat;
    private final ProposalsResourceAsync proposals;
    private final FeedResourceAsync feed;
    private final BatchesResourceAsync batches;
    private final EsfResourceAsync esf;
    private final FormsResourceAsync forms;
    private final OnecResourceAsync onec;
    private final AutomationsResourceAsync automations;

    TungoAsyncClient(TungoClient sync, HttpEngine engine) {
        this.sync = sync;
        this.engine = engine;
        this.workspaces = new WorkspacesResourceAsync(sync.workspaces(), engine);
        this.companies = new CompaniesResourceAsync(sync.companies(), engine);
        this.platform = new PlatformResourceAsync(sync.platform(), engine);
        this.apiKeys = new ApiKeysResourceAsync(sync.apiKeys(), engine);
        this.webhooks = new WebhooksResourceAsync(sync.webhooks(), engine);
        this.chat = new ChatResourceAsync(sync.chat(), engine);
        this.proposals = new ProposalsResourceAsync(sync.proposals(), engine);
        this.feed = new FeedResourceAsync(sync.feed(), engine);
        this.batches = new BatchesResourceAsync(sync.batches(), engine);
        this.esf = new EsfResourceAsync(sync.esf(), engine);
        this.forms = new FormsResourceAsync(sync.forms(), engine);
        this.onec = new OnecResourceAsync(sync.onec(), engine);
        this.automations = new AutomationsResourceAsync(sync.automations(), engine);
    }

    /**
     * Returns the synchronous client this view belongs to.
     *
     * @return the sync client
     */
    public TungoClient sync() {
        return sync;
    }

    /**
     * Организации (workspace), доступные ключу API.
     *
     * @return the <code>workspaces</code> resource
     */
    public WorkspacesResourceAsync workspaces() {
        return workspaces;
    }

    /**
     * Компании организации и их карточки.
     *
     * @return the <code>companies</code> resource
     */
    public CompaniesResourceAsync companies() {
        return companies;
    }

    /**
     * Баланс платформы, книга движений, отчёты о расходе, лимиты, аудит и тарифы моделей.
     *
     * @return the <code>platform</code> resource
     */
    public PlatformResourceAsync platform() {
        return platform;
    }

    /**
     * Сведения о текущем ключе API и статистика его расхода.
     *
     * @return the <code>apiKeys</code> resource
     */
    public ApiKeysResourceAsync apiKeys() {
        return apiKeys;
    }

    /**
     * Исходящие вебхуки организации (Svix): адреса, каталог событий, доставки и проверка подписи.
     *
     * @return the <code>webhooks</code> resource
     */
    public WebhooksResourceAsync webhooks() {
        return webhooks;
    }

    /**
     * Диалоги с ИИ-бухгалтером: вопросы, потоковый чат (SSE), подсчёт токенов и история.
     *
     * @return the <code>chat</code> resource
     */
    public ChatResourceAsync chat() {
        return chat;
    }

    /**
     * Предложения документов ИИ-бухгалтера: просмотр, одобрение записи в 1С и отклонение.
     *
     * @return the <code>proposals</code> resource
     */
    public ProposalsResourceAsync proposals() {
        return proposals;
    }

    /**
     * Лента событий и предложений компании (страницы и живой поток SSE).
     *
     * @return the <code>feed</code> resource
     */
    public FeedResourceAsync feed() {
        return feed;
    }

    /**
     * Пачки обработки банковских выписок, входящих ЭСФ и DirectBank.
     *
     * @return the <code>batches</code> resource
     */
    public BatchesResourceAsync batches() {
        return batches;
    }

    /**
     * Входящие и исходящие электронные счета-фактуры (ИС ЭСФ).
     *
     * @return the <code>esf</code> resource
     */
    public EsfResourceAsync esf() {
        return esf;
    }

    /**
     * Налоговые формы (ФНО 100.00 и др.): сборка, проверка ИСНА и разрывы данных.
     *
     * @return the <code>forms</code> resource
     */
    public FormsResourceAsync forms() {
        return forms;
    }

    /**
     * Состояние подключения компании к базе 1С.
     *
     * @return the <code>onec</code> resource
     */
    public OnecResourceAsync onec() {
        return onec;
    }

    /**
     * Автоматизации компании: входящие хуки, сценарии, цепочки шагов, запуски и уровень автономности
     * ИИ.
     *
     * @return the <code>automations</code> resource
     */
    public AutomationsResourceAsync automations() {
        return automations;
    }

    /**
     * Asks the AI accountant one question and completes with the whole answer (a non-streaming
     * chat turn, default timeout 600 s).
     *
     * @param companyId id of the company ({@code co_...})
     * @param message the question
     * @return a future of the result of the turn
     */
    public CompletableFuture<ChatTurnResult> ask(String companyId, String message) {
        return engine.async(() -> sync.ask(companyId, message));
    }

    /**
     * Asks the AI accountant one question and completes with the whole answer.
     *
     * @param companyId id of the company ({@code co_...})
     * @param message the question
     * @param options per-request options
     * @return a future of the result of the turn
     */
    public CompletableFuture<ChatTurnResult> ask(String companyId, String message, RequestOptions options) {
        return engine.async(() -> sync.ask(companyId, message, options));
    }

    /**
     * Asks the AI accountant one question and completes with the open stream as soon as the
     * response headers arrive.
     *
     * @param companyId id of the company ({@code co_...})
     * @param message the question
     * @return a future of the open stream; close the stream when done
     */
    public CompletableFuture<ChatStream> askStream(String companyId, String message) {
        return engine.async(() -> sync.askStream(companyId, message));
    }

    /**
     * Posts a JSON event to an incoming hook ({@code POST /hooks/{token}}).
     *
     * @param token the hook token from the hook URL
     * @param payload any value Jackson can serialize
     * @param secret the hook secret that signs the exact bytes sent; null sends it unsigned
     * @return a future of the acknowledgement of the hook
     */
    public CompletableFuture<AutomationHookAccepted> sendHook(String token, Object payload, String secret) {
        return engine.async(() -> sync.sendHook(token, payload, secret));
    }

    /**
     * Posts an event to an incoming hook ({@code POST /hooks/{token}}).
     *
     * @param token the hook token from the hook URL
     * @param request the body, its content type, and optionally a secret and an event id
     * @return a future of the acknowledgement of the hook
     */
    public CompletableFuture<AutomationHookAccepted> sendHook(String token, HookRequest request) {
        return engine.async(() -> sync.sendHook(token, request));
    }

    /**
     * Verifies a webhook with the client's webhook secret. Verification is local and immediate,
     * so this method is synchronous.
     *
     * @param body the raw request body, exactly as received
     * @param headers the request headers
     * @return the verified event
     * @throws kz.tungo.sdk.errors.WebhookVerificationException when the webhook cannot be trusted
     */
    public WebhookEvent verifyWebhook(String body, Map<String, ?> headers) {
        return sync.verifyWebhook(body, headers);
    }

    /**
     * Waits until an automation run reaches a final state.
     *
     * @param companyId id of the company ({@code co_...})
     * @param runId id of the run
     * @param options deadline, polling intervals and request options; null for the defaults
     * @return a future of the run in its final state
     */
    public CompletableFuture<AutomationRunDetail> waitForRun(String companyId, String runId, WaitOptions options) {
        return engine.async(() -> sync.waitForRun(companyId, runId, options));
    }

    /**
     * Starts a scenario manually and waits until its run reaches a final state.
     *
     * @param companyId id of the company ({@code co_...})
     * @param scenarioId id of the scenario
     * @param input the optional run input, may be null
     * @param options deadline, polling intervals and request options; null for the defaults
     * @return a future of the run in its final state
     */
    public CompletableFuture<AutomationRunDetail> runScenarioAndWait(String companyId, String scenarioId,
        AutomationScenarioRunInput input, WaitOptions options) {
        return engine.async(() -> sync.runScenarioAndWait(companyId, scenarioId, input, options));
    }

    /**
     * Waits until a batch job stops running.
     *
     * @param companyId id of the company ({@code co_...})
     * @param batchId id of the batch job
     * @param options deadline, polling intervals and request options; null for the defaults
     * @return a future of the finished job
     */
    public CompletableFuture<BatchJob> waitForBatch(String companyId, String batchId, WaitOptions options) {
        return engine.async(() -> sync.waitForBatch(companyId, batchId, options));
    }

    /**
     * Describes the client without revealing credentials.
     *
     * @return a description of the underlying client
     */
    @Override
    public String toString() {
        return "TungoAsyncClient{" + sync + "}";
    }
}
