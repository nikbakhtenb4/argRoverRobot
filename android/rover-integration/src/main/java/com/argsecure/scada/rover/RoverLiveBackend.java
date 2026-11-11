package com.argsecure.scada.rover;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.argsecure.webserver.ArgAgentProduct;
import com.argsecure.webserver.apiv2.ArgApiV2_Agent;
import com.argsecure.webserver.util.ArgJson_Serializer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Client delegations only. Normal GPT-Live speech never enters the old STT/chat/TTS chain. */
public final class RoverLiveBackend implements AutoCloseable {
    public interface Listener {
        void send(JsonObject event);
        default void onBusy(boolean busy) { }
        default void onError(String message) { }
        default void onNotice(String message) { }
    }
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final RoverLiveDelegationState state = new RoverLiveDelegationState();
    private final RoverLiveResultDelivery delivery = new RoverLiveResultDelivery();
    private final RoverKnowledgeService knowledge;
    private final ArrayDeque<String> priorUserRequests = new ArrayDeque<>();
    private boolean closed, busy;
    private RoverLiveDelegationState.Work activeWork;
    private RoverWorkStatus visualWork = RoverWorkStatus.NONE;
    private final Runnable pump = this::drain;
    private volatile String lastIssueCode = "", lastIssuePhase = "", lastIssueKind = "";
    private volatile long issueCount, lastIssueAt;
    private volatile JsonObject lastResult;
    private volatile long delegationReceived, delegationAccepted, delegationRejected, workStarted;
    private volatile long localVisualReads;
    private volatile long lastDelegationOffset = -1;
    private volatile String lastDelegationRejection = "";

    public RoverLiveBackend(Context context, Listener listener) {
        this(context, listener, new RoverKnowledgeService(context));
    }
    RoverLiveBackend(Context context, Listener listener, RoverKnowledgeService knowledge) {
        this.context = context.getApplicationContext(); this.listener = listener;
        this.knowledge = knowledge;
    }

    /** Call on main for every new transport generation, including disconnection/background. */
    public void reset() {
        if (closed) return;
        main.removeCallbacks(pump);
        state.reset(); delivery.reset(); activeWork = null; visualWork = RoverWorkStatus.NONE; priorUserRequests.clear(); RoverResearchPresentation.clear();
        updateBusy();
    }

    /** Exact local wake opens the turn; separate native request proof is still required. */
    public void resetForWake() { reset(); state.confirmLocalWake(); }

    /** Main thread, called only for locally confirmed request speech after the acoustic name. */
    public boolean confirmNativeRequest(long requestStartMs) {
        if (closed || !state.confirmNativeRequest(requestStartMs)) return false;
        if (state.pendingCount() > 0) schedule(0);
        return true;
    }

    public boolean confirmNativeInputThrough(long endMs) {
        boolean confirmed = !closed && state.confirmNativeInputThrough(endMs);
        if (confirmed) schedule(0);
        return confirmed;
    }

    /** Any thread: revoke dispatch immediately, even while the main handler is delayed. */
    public void invalidateIncompleteInputDispatch() {
        state.invalidateIncompleteInput();
    }

    /** Main thread: finish cleanup after revoking an incomplete input epoch's dispatch token. */
    public void invalidateIncompleteInput() {
        if (closed) return;
        main.removeCallbacks(pump);
        invalidateIncompleteInputDispatch(); delivery.reset(); activeWork = null; visualWork = RoverWorkStatus.NONE;
        RoverResearchPresentation.clear();
        updateBusy();
    }

    /** Main thread, only after native admission of one name-only continuation within five seconds. */
    public void allowWakeContinuation() {
        if (!closed) state.allowWakeContinuation();
    }

    /**
     * Main thread, after native admission of a pre-reply continuation. The floor is
     * its first input sample on this transport, including any earlier queued PCM.
     * Preserve prior input callbacks on the same logical turn; invalidate only their
     * old output/delegation callbacks before calling this method.
     */
    public boolean beginInputContinuation(long firstInputMs) {
        if (closed || !state.beginInputContinuation(firstInputMs)) return false;
        main.removeCallbacks(pump);
        delivery.reset(); activeWork = null; visualWork = RoverWorkStatus.NONE;
        RoverResearchPresentation.clear();
        updateBusy();
        return true;
    }

    /**
     * Main thread, before forwarding a follow-up admitted by the native attention controller.
     * The timestamp is its first uploaded sample on the current Live transport. This is not
     * callable from model text: initial wake verification and attention expiry remain native.
     */
    public void beginAttentionTurn(long inputStartMs) {
        if (closed || inputStartMs < 0) return;
        main.removeCallbacks(pump);
        state.beginAttentionTurn(inputStartMs);
        delivery.reset();
        RoverResearchPresentation.clear();
        activeWork = null;
        visualWork = RoverWorkStatus.NONE;
        updateBusy();
    }

    /** Prefer the timestamp overload when reusing a transport. */
    public void beginAttentionTurn() {
        beginAttentionTurn(state.lastInputEndMs());
    }

    /** Main-thread entry point for parsed events from the current authenticated Live connection. */
    public void acceptEvent(JsonObject event) {
        if (closed || event == null) return;
        try {
            String type = string(event, "type");
            String eventId = string(event, "event_id");
            long now = SystemClock.elapsedRealtime();
            if ("session.thinking.appended".equals(type) || "session.commentary.appended".equals(type)) {
                // A delayed main-thread ACK must not win a race against the timer and
                // revive an expired delivery or release its final spoken instruction.
                if (delivery.expire(now)) { deliveryFailed(); return; }
                sendAppends(delivery.acknowledge(type, string(event, "client_event_id"), now));
                updateBusy(); schedule(0);
            } else if ("error".equals(type)) {
                handlesProtocolError(event);
            } else if ("session.input_transcript.delta".equals(type) || "session.output_transcript.delta".equals(type)) {
                long before = state.inputRevision();
                boolean accepted = state.transcript(eventId, "session.input_transcript.delta".equals(type),
                        string(event, "delta"), timestamp(event, "start_ms"), timestamp(event, "end_ms"), now);
                if (state.inputRevision() != before && delivery.active()) delivery.reset();
                if (accepted && (state.pendingCount() > 0 || "session.input_transcript.delta".equals(type))) schedule(0);
                updateBusy();
            } else if ("session.delegation.created".equals(type)) {
                delegationReceived++; lastDelegationOffset = timestamp(event, "offset_ms");
                JsonObject delegation = event.getAsJsonObject("delegation");
                if (delegation != null && "client".equals(string(delegation, "target"))
                        && state.delegate(eventId, string(delegation, "id"), timestamp(event, "offset_ms"), now)) {
                    delegationAccepted++; lastDelegationRejection = "";
                    updateBusy(); schedule(0);
                } else {
                    delegationRejected++;
                    lastDelegationRejection = delegation == null || !"client".equals(string(delegation, "target"))
                            ? "invalid_target" : state.lastDelegationRejection();
                }
            }
        } catch (RuntimeException malformed) {
            // Missing/invalid protocol metadata cannot become a command.
        }
    }

    public boolean isBusy() { return busy; }

    /** Main-thread UI read. Stale/revoked work cannot keep a search icon alive. */
    public RoverWorkStatus getVisualStatus() {
        if (closed || activeWork != null && !state.current(activeWork)) return RoverWorkStatus.NONE;
        return visualWork;
    }

    private void visualProgress(RoverLiveDelegationState.Work work, RoverWorkStatus phase) {
        main.post(() -> {
            if (!closed && activeWork == work && state.current(work)) visualWork = phase;
        });
    }

    /** Main thread: native transport reports an outgoing append that could not be queued. */
    public void onSendRejected(String eventId) {
        if (closed) return;
        if (delivery.reject(eventId, "send_rejected")) deliveryFailed();
    }

    /** Main thread. A correlated append failure is local to that result, not a fatal session error. */
    public boolean handlesProtocolError(JsonObject event) {
        if (closed || event == null || !"error".equals(string(event, "type"))) return false;
        JsonElement error = event.get("error");
        if (error == null || !error.isJsonObject()) return false;
        String eventId = string(error.getAsJsonObject(), "client_event_id");
        if (!delivery.knows(eventId)) return false;
        if (delivery.reject(eventId, "append_rejected")) deliveryFailed();
        return true;
    }

    private void deliveryFailed() {
        recordIssue("delivery", delivery.failure(), "failed");
        listener.onError("نتیجهٔ ابزار آماده شد، اما تحویل آن به صدای زنده تأیید نشد؛ فرمان خودکار تکرار نمی‌شود.");
        updateBusy(); schedule(0);
    }

    private void schedule(long delay) {
        if (closed) return;
        main.removeCallbacks(pump); main.postDelayed(pump, Math.max(0, delay));
    }

    private void drain() {
        if (closed) return;
        if (delivery.expire(SystemClock.elapsedRealtime())) deliveryFailed();
        if (delivery.active()) { schedule(Math.min(500, delivery.remaining(SystemClock.elapsedRealtime()))); return; }
        RoverLiveDelegationState.Decision decision = state.poll(SystemClock.elapsedRealtime());
        if (decision == null) decision = state.pollVisualRead(SystemClock.elapsedRealtime());
        if (decision == null) { updateBusy(); return; }
        if (decision.delayMs >= 0) { schedule(decision.delayMs); return; }
        if (decision.work == null) {
            if (decision.silent) sendAppends(delivery.beginSilent(decision.delegationId, decision.reply, SystemClock.elapsedRealtime()));
            else commentary(decision.delegationId, decision.reply);
            updateBusy(); schedule(0); return;
        }
        final RoverLiveDelegationState.Work work = decision.work;
        workStarted++;
        if (work.localRead) localVisualReads++;
        activeWork = work; visualWork = RoverWorkStatus.PLANNING; updateBusy();
        StringBuilder prior = new StringBuilder(RoverWorkingMemory.priorUserContext(System.currentTimeMillis()));
        for (String previous : priorUserRequests) prior.append(previous).append("\n");
        priorUserRequests.addLast(work.userText);
        while (priorUserRequests.size() > 6) priorUserRequests.removeFirst();
        RoverResearchPresentation.clear();
        worker.execute(() -> execute(work, prior.toString()));
    }

    private void execute(RoverLiveDelegationState.Work work, String priorUserContext) {
        String result;
        String receipt = "delegation=" + work.delegationId + "; status=not_confirmed";
        boolean failed = false, knowledgeResult = false, clarification = false;
        String diagnosticCode = "";
        final RoverWorkStatus[] phase = { RoverWorkStatus.PLANNING };
        RoverOnlineResearch.Result research = null;
        try {
            if (!state.current(work)) throw new CancellationException();
            RoverKnowledgeService.Outcome answer;
            String motionDirection=RoverMotionIntent.direction(work.userText);
            if (motionDirection!=null) {
                visualProgress(work,RoverWorkStatus.COMMAND);
                answer=new RoverKnowledgeService.Outcome(RoverMotionBridge.request(context,work.delegationId,"human",motionDirection,()->state.current(work)),"motion_request",null);
            } else if (work.visualRead) {
                if (!RoverVisionQuestions.readOnly(work.userText)) throw new IllegalStateException("Invalid local visual read");
                String observation = RoverAgentHub.get().callTask(RoverAgentHub.Agent.VISION,
                        guard -> RoverVisionAnswers.fresh(context, work.userText, guard), () -> state.current(work), 12_000);
                answer = new RoverKnowledgeService.Outcome(observation,
                        work.localRead ? "current_vision_local_read" : "current_vision", null);
            } else answer = knowledge.execute(work.userText, priorUserContext, () -> state.current(work),
                    value -> { phase[0] = value; visualProgress(work, value); });
            if (answer != null) {
                result = answer.text; receipt = answer.receipt; research = answer.research; knowledgeResult = true;
                diagnosticCode = answer.diagnosticCode;
                clarification = "weather_place_needed".equals(answer.receipt)
                        || "request_clarification".equals(answer.receipt)
                        || "knowledge_correction_needed".equals(answer.receipt)
                        || answer.research != null && answer.research.type == RoverOnlineResearch.Type.CLARIFICATION;
            } else {
            visualProgress(work, RoverWorkStatus.COMMAND);
            phase[0] = RoverWorkStatus.COMMAND;
            ArgAgentProduct.requireReady(context);
            ArgApiV2_Agent agent = new ArgApiV2_Agent(context, new ArgJson_Serializer());
            // Only the exact current user request, never assistant speech or earlier commands.
            JsonObject response = RoverAgentHub.get().callTask(RoverAgentHub.Agent.PHOENIX,
                    guard -> agent.executeVoiceCommand(work.userText, guard::getAsBoolean), () -> state.current(work), 90_000);
            JsonArray actions = response.has("actions") && response.get("actions").isJsonArray()
                    ? response.getAsJsonArray("actions") : new JsonArray();
            int succeeded = 0, submitted = 0;
            for (JsonElement value : actions) {
                if (value.isJsonObject() && "submitted".equals(string(value.getAsJsonObject(), "completion"))) submitted++;
                if (value.isJsonObject() && value.getAsJsonObject().has("success")
                        && value.getAsJsonObject().get("success").getAsBoolean()) succeeded++;
            }
            receipt = "delegation=" + work.delegationId + "; actions=" + actions;
            boolean actionRequired = response.has("actionRequired") && response.get("actionRequired").getAsBoolean();
            if (actionRequired && actions.size() == 0)
                result = "ابزار اجرایی فراخوانی نشد؛ هیچ فرمانی اجرا نشده است. موفقیت را اعلام نکن.";
            else if (succeeded < actions.size())
                result = "نتیجهٔ واقعی فونیکس: فقط " + succeeded + " از " + actions.size()
                        + " اقدام موفق بود. موفقیت کامل را اعلام نکن و فرمان را خودکار تکرار نکن.";
            else result = (submitted > 0 ? "فرمان ارسال شد؛ تغییر فیزیکی تجهیزات هنوز با بازخورد تأیید نشده است. انجام‌شدن را اعلام نکن. "
                        : actions.size() > 0 ? "نتیجهٔ تأییدشدهٔ ابزار: " + succeeded + " اقدام موفق. " : "نتیجهٔ بررسی فونیکس: ")
                        + string(response, "text");
            }
        } catch (CancellationException cancelled) {
            result = "با تغییر گفتار یا پایان اتصال، ادامهٔ درخواست قبلی لغو شد. نتیجهٔ آن کامل تأیید نشده؛ خودکار تکرارش نکن.";
        } catch (Exception error) {
            failed = true;
            diagnosticCode = issueCode(error);
            result = "ابزار این درخواست پاسخ کامل نداد؛ نتیجه یا ذخیره‌شدن اطلاعات تأیید نشده است. دادهٔ زنده و موفقیت را حدس نزن؛ کوتاه بگو دریافت نتیجه ممکن نشد.";
        }
        final boolean expanded = knowledgeResult && result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 500;
        final java.util.List<String> details = expanded ? RoverLiveResultContext.split(result) : java.util.Collections.emptyList();
        final String answer = expanded ? expandedResultInstruction() : RoverLiveDelegationState.limit(result, 500);
        final String savedReceipt = receipt;
        final String auditResult = RoverLiveDelegationState.limit(result, 1800);
        final boolean showError = failed;
        final String issueCode = diagnosticCode, issuePhase = phase[0].name();
        final boolean needsClarification = clarification;
        final RoverOnlineResearch.Result deliveredResearch = research;
        main.post(() -> {
            boolean deliver = state.finish(work, answer, savedReceipt);
            if (activeWork == work) {
                activeWork = null;
                visualWork = deliver && !showError && needsClarification ? RoverWorkStatus.CLARIFICATION : RoverWorkStatus.NONE;
            }
            if (!closed && deliver) {
                JsonObject evidence=new JsonObject();evidence.addProperty("at_elapsed_ms",SystemClock.elapsedRealtime());
                evidence.addProperty("user",RoverLiveDelegationState.limit(work.userText,600));
                evidence.addProperty("receipt",savedReceipt);evidence.addProperty("result",auditResult);lastResult=evidence;
                if (!issueCode.isEmpty()) recordIssue(issuePhase, issueCode, showError ? "failed" : "clarification");
                // Result delivery still owns this turn. Do not end observation or ask for
                // another wake before its spoken fallback has had a chance to arrive.
                if (showError) listener.onNotice("پاسخ این درخواست آماده نشد؛ گفت‌وگو همچنان فعاله.");
                if (deliveredResearch != null) {
                    RoverResearchPresentation.publish(deliveredResearch);
                    knowledge.indexDelivered(deliveredResearch);
                }
                sendAppends(delivery.begin(work.delegationId, details, answer, SystemClock.elapsedRealtime()));
            }
            updateBusy();
            if (!closed) schedule(0);
        });
    }

    static String issueCode(Exception failure) {
        if (failure instanceof RoverRequestPlanner.InvalidPlanException)
            return ((RoverRequestPlanner.InvalidPlanException) failure).code;
        if (failure instanceof RoverReadOnlyHttp.ResultFailure)
            return ((RoverReadOnlyHttp.ResultFailure) failure).code;
        if (failure instanceof java.util.concurrent.TimeoutException) return "timeout";
        if (failure instanceof java.io.IOException) return "io_failure";
        return "execution_failure"; // Never retain exception messages, provider bodies or credentials.
    }

    private void recordIssue(String phase, String code, String kind) {
        lastIssuePhase = phase; lastIssueCode = code; lastIssueKind = kind;
        lastIssueAt = SystemClock.elapsedRealtime(); issueCount++;
    }

    /** Private diagnostics only; never included in tool results or public runtime. */
    JsonObject diagnostics() {
        JsonObject value = new JsonObject(); value.addProperty("issue_count", issueCount);
        value.addProperty("last_issue_code", lastIssueCode); value.addProperty("last_issue_phase", lastIssuePhase);
        value.addProperty("last_issue_kind", lastIssueKind); value.addProperty("last_issue_elapsed_ms", lastIssueAt);
        value.addProperty("delegation_received", delegationReceived); value.addProperty("delegation_accepted", delegationAccepted);
        value.addProperty("delegation_rejected", delegationRejected); value.addProperty("delegation_rejection", lastDelegationRejection);
        value.addProperty("delegation_offset_ms", lastDelegationOffset); value.addProperty("work_started", workStarted);
        value.addProperty("local_visual_reads", localVisualReads);
        value.addProperty("pending", state.pendingCount()); value.addProperty("busy", busy);
        value.addProperty("work_active", activeWork != null); value.addProperty("delivery_active", delivery.active());
        JsonObject evidence=lastResult;if(evidence!=null)value.add("last_result",evidence.deepCopy());
        return value;
    }

    private static String expandedResultInstruction() {
        return "اطلاعات نتیجه در پیام‌های زمینهٔ همین درخواست رسید. با استفاده از بخش‌های مرتبط، پاسخ روشن و طبیعی بده؛ "
                + "توضیحات داخلی را نخوان، چیزی به‌عنوان خاطره یا دادهٔ تازه نساز و اگر ابهام یا اختلافی هست روشن کن.";
    }

    private void commentary(String delegationId, String content) {
        if (closed) return;
        sendAppends(delivery.begin(delegationId, java.util.Collections.emptyList(),
                RoverLiveDelegationState.limit(content, 500), SystemClock.elapsedRealtime()));
    }

    private void sendAppends(java.util.List<RoverLiveResultDelivery.Append> appends) {
        for (RoverLiveResultDelivery.Append append : appends) {
            if (!delivery.active()) break;
            JsonObject event = new JsonObject(); event.addProperty("type", append.type);
            event.addProperty("event_id", append.eventId); event.addProperty("delegation_id", append.delegationId);
            event.addProperty("content", append.content); listener.send(event);
        }
    }

    private void updateBusy() {
        boolean value = !closed && (state.pendingCount() > 0 || activeWork != null || delivery.active());
        if (busy != value) { busy = value; listener.onBusy(value); }
    }

    @Override public void close() {
        if (closed) return;
        closed = true; state.close(); delivery.reset(); activeWork = null; visualWork = RoverWorkStatus.NONE;
        main.removeCallbacks(pump); worker.shutdownNow(); knowledge.close(); priorUserRequests.clear(); updateBusy();
    }

    private static String string(JsonObject value, String key) {
        return value != null && value.has(key) && value.get(key).isJsonPrimitive()
                && value.getAsJsonPrimitive(key).isString() ? value.get(key).getAsString() : "";
    }
    private static long timestamp(JsonObject value, String key) {
        if (value == null || !value.has(key) || !value.get(key).isJsonPrimitive()
                || !value.getAsJsonPrimitive(key).isNumber()) return -1;
        double number = value.get(key).getAsDouble();
        return Double.isNaN(number) || Double.isInfinite(number) || number < 0 || number > 86_400_000
                ? -1 : (long) number;
    }
}
