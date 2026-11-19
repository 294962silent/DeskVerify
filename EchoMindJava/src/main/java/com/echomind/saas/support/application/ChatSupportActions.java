package com.echomind.saas.support.application;

import com.echomind.agent.HandoffPolicy;
import com.echomind.agent.HandoffSignals;
import com.echomind.intent.IntentCategory;
import com.echomind.saas.api.error.ResourceNotFoundException;
import com.echomind.saas.commerce.application.CommerceAccessPolicy;
import com.echomind.saas.commerce.application.CommerceQuery;
import com.echomind.saas.commerce.domain.CommerceOrder;
import com.echomind.saas.commerce.domain.CustomerId;
import com.echomind.saas.identity.domain.ActorScope;
import com.echomind.saas.ticket.application.CreateTicketCommand;
import com.echomind.saas.ticket.application.TicketCommands;
import com.echomind.trace.ToolCallTrace;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class ChatSupportActions {

    private static final Pattern EXPLICIT_HANDOFF = Pattern.compile(
            "(?iu)(?:(?:(?:你好[，,\\s]*)?(?:麻烦|请|帮我|我要|我想要?|可以)?\\s*)"
                    + "(?:(?:帮我)?(?:转接|转给|转|接通|联系|找)\\s*人工(?:客服)?|人工客服)"
                    + "(?:谢谢|吗|吧|一下)?"
                    + "(?:[，,]\\s*(?:找|请|让)人工客服(?:处理|跟进)(?:一下)?(?:工单|这个问题))?"
                    + "|(?:please\\s+)?(?:transfer me to|connect me (?:to|with)|"
                    + "i (?:want|need) to (?:speak|talk) (?:to|with))\\s+(?:a\\s+)?"
                    + "(?:human(?: agent)?|live agent)(?:\\s+please)?)"
                    + "[\\s。！？.!?]*");
    private static final Pattern ORDER_ID = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_-])((?:[A-Za-z0-9]+[-_])*order[-_][A-Za-z0-9]+(?:[-_][A-Za-z0-9]+)*)"
                    + "(?![A-Za-z0-9_-])|(?:订单号(?:是|为)?\\s*[:：#]?|order\\s*(?:id|number)\\s*[:#]?|#)"
                    + "\\s*([A-Za-z0-9][A-Za-z0-9_-]{0,127})(?![A-Za-z0-9_-])");
    private static final Pattern CUSTOMER_ID = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_-])(?:[A-Za-z0-9]+[-_])*customer[-_][A-Za-z0-9]+"
                    + "(?:[-_][A-Za-z0-9]+)*(?![A-Za-z0-9_-])");
    private static final Pattern ORDER_STATUS = Pattern.compile(
            "(?iu)(?:我的订单|最近.{0,5}订单|(?:查询|查看|查一下|查下|查)\\s*(?:我的)?\\s*订单"
                    + "|订单(?:状态|进度|记录|详情|到哪|有哪些|在哪里|发货了吗|号(?:和|与|及)状态)"
                    + "|\\b(?:my orders?|recent orders|order status|(?:check|find) (?:my|the) order)\\b)"
                    + "|(?:查询|查看|查一下|查下|查|列出|列一下).{0,145}订单");
    private static final Pattern LOGISTICS = Pattern.compile(
            "(?iu)(?:查.{0,8}(?:物流|快递|运单)|(?:物流|快递|配送)(?:状态|进度|信息|到哪)"
                    + "|(?:我的|这个|那笔)\\s*(?:物流|快递|包裹|订单.{0,5}(?:物流|配送|发货))"
                    + "|发货了[吗没]|什么时候发货|\\b(?:tracking|shipment status|shipping status"
                    + "|track (?:my|this|the) (?:order|package))\\b)");
    private static final Pattern LOGISTICS_TOPIC = Pattern.compile(
            "(?iu)(?:物流|快递|配送|运单|发货|\\b(?:ship(?:ped|ping)?|tracking|delivery|track)\\b)");
    private static final Pattern NON_STATUS_REQUEST = Pattern.compile(
            "(?iu)(?:退款|退货|取消|改地址|改订单|修改订单|发票|政策|规则|说明|如何|怎么查"
                    + "|\\b(?:refund|cancel|return|invoice|policy|how to)\\b)");
    private static final String COMMERCE_LOOKUP = "commerce_order_lookup";
    private static final String TICKET_CREATE = "ticket_create";
    private static final int MAX_CUSTOMERS_FOR_EXACT_LOOKUP = 10;
    private static final int MAX_ORDERS_DISPLAYED = 5;

    private final CommerceQuery commerce;
    private final CommerceAccessPolicy commerceAccess;
    private final TicketCommands tickets;
    private final HandoffPolicy handoff;

    public ChatSupportActions(
            CommerceQuery commerce,
            CommerceAccessPolicy commerceAccess,
            TicketCommands tickets,
            HandoffPolicy handoff) {
        this.commerce = commerce;
        this.commerceAccess = commerceAccess;
        this.tickets = tickets;
        this.handoff = handoff;
    }

    public Optional<Result> handle(
            String message, String customerId, String conversationId, ActorScope actor) {
        if (EXPLICIT_HANDOFF.matcher(message.strip()).matches()) {
            var decision = handoff.decide(
                    new HandoffSignals(true, false, false, false, false, false));
            if (decision.handoff()) {
                return Optional.of(createTicket(customerId, conversationId, actor, decision.reason()));
            }
        }
        if (NON_STATUS_REQUEST.matcher(message).find()) {
            return Optional.empty();
        }
        var order = ORDER_ID.matcher(message);
        String orderId = order.find() ? (order.group(1) == null ? order.group(2) : order.group(1)) : null;
        boolean logistics = LOGISTICS.matcher(message).find()
                || (orderId != null && LOGISTICS_TOPIC.matcher(message).find());
        if (logistics || orderId != null || ORDER_STATUS.matcher(message).find()) {
            IntentCategory intent = logistics ? IntentCategory.LOGISTICS : IntentCategory.ORDER_STATUS;
            List<String> namedCustomers = CUSTOMER_ID.matcher(message).results()
                    .map(match -> match.group()).distinct().limit(2).toList();
            if (namedCustomers.size() > 1) {
                return Optional.of(new Result(intent, "请一次指定一个客户账户。",
                        "AMBIGUOUS_CUSTOMER_SELECTION", orderId, null, null));
            }
            String selectedCustomer = namedCustomers.isEmpty() ? customerId : namedCustomers.getFirst();
            return Optional.of(lookupOrders(selectedCustomer, actor, orderId, logistics));
        }
        return Optional.empty();
    }

    private Result lookupOrders(String customerId, ActorScope actor, String orderId, boolean logistics) {
        IntentCategory intent = logistics ? IntentCategory.LOGISTICS : IntentCategory.ORDER_STATUS;
        List<CustomerId> customers = customersFor(customerId, actor, orderId != null);
        if (customers.isEmpty()) {
            return clarify(intent, actor, orderId);
        }

        long started = System.nanoTime();
        List<CommerceOrder> orders = new ArrayList<>();
        for (CustomerId customer : customers) {
            commerce.load(customer, actor).orders().stream()
                    .filter(order -> orderId == null || order.orderId().equalsIgnoreCase(orderId))
                    .forEach(orders::add);
        }
        String answer;
        if (orders.isEmpty()) {
            answer = orderId == null ? "当前客户账户没有订单记录。"
                    : "在当前可查询的客户范围内未找到订单 " + orderId + "。";
        } else {
            List<String> statuses = orders.stream().limit(MAX_ORDERS_DISPLAYED)
                    .map(order -> "- " + order.orderId() + "：订单状态 " + order.status()
                            + (order.fulfilledAt() == null
                                    ? "；暂无履约时间记录"
                                    : "；履约时间 " + order.fulfilledAt()))
                    .toList();
            answer = "订单记录：\n" + String.join("\n", statuses);
            if (orders.size() > MAX_ORDERS_DISPLAYED) {
                answer += "\n另有订单未列出，可提供订单号查看指定订单。";
            }
            if (logistics) {
                answer += "\n订单数据没有快递单号或运输轨迹，无法确认当前配送位置。";
            }
        }
        return new Result(intent, answer, "AUTHORIZED_ORDER_LOOKUP", orderId, null,
                trace(COMMERCE_LOOKUP, started, false));
    }

    private Result createTicket(String customerId, String conversationId, ActorScope actor, String reason) {
        List<CustomerId> customers = customersFor(customerId, actor, false);
        if (customers.isEmpty()) {
            return clarify(IntentCategory.HUMAN_HANDOFF, actor, null);
        }
        String customer = customers.getFirst().value();
        long started = System.nanoTime();
        var creation = tickets.create(new CreateTicketCommand(
                customer, "聊天转人工", "用户在聊天中明确请求人工客服。",
                null, null, conversationId, null, handoffKey(actor, customer, conversationId)), actor);
        String ticketId = creation.ticket().ticketId().value();
        String answer = creation.replayed()
                ? "本会话已提交人工客服工单，工单号：" + ticketId + "。"
                : "已提交人工客服工单，工单号：" + ticketId + "。初始状态：" + creation.ticket().status() + "。";
        return new Result(IntentCategory.HUMAN_HANDOFF, answer, reason, null, ticketId,
                trace(TICKET_CREATE, started, creation.replayed()));
    }

    private List<CustomerId> customersFor(String customerId, ActorScope actor, boolean exactOrder) {
        if (customerId != null && !customerId.isBlank()) {
            CustomerId customer = new CustomerId(customerId.strip());
            commerceAccess.requireCustomer(actor, customer);
            return List.of(customer);
        }
        int count = actor.customerIds().size();
        if (count == 1 || (exactOrder && count <= MAX_CUSTOMERS_FOR_EXACT_LOOKUP)) {
            return actor.customerIds().stream().sorted().map(CustomerId::new).toList();
        }
        return List.of();
    }

    private Result clarify(IntentCategory intent, ActorScope actor, String orderId) {
        boolean noCustomers = actor.customerIds().isEmpty();
        return new Result(intent, noCustomers ? "当前登录用户没有关联的客户账户。"
                : "请指定已关联的客户账户后再试。", noCustomers ? "NO_CUSTOMER_SCOPE"
                        : "CUSTOMER_SELECTION_REQUIRED", orderId, null, null);
    }

    private static ToolCallTrace trace(String name, long started, boolean replayed) {
        return new ToolCallTrace(name, true, false, replayed, false,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), "");
    }

    private static String handoffKey(ActorScope actor, String customer, String conversation) {
        // Retries and wording changes in the same conversation reuse the ticket command.
        List<String> parts = List.of(actor.tenantId().value(), actor.userId().value(), customer, conversation);
        StringBuilder scopedKey = new StringBuilder();
        parts.forEach(part -> scopedKey.append(part.length()).append(':').append(part));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(scopedKey.toString().getBytes(StandardCharsets.UTF_8));
            return "chat-handoff-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Result(
            IntentCategory intent,
            String answer,
            String reason,
            String orderId,
            String ticketId,
            ToolCallTrace toolCall) {}
}
