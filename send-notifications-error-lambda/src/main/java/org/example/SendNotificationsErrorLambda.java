package org.example;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SendNotificationsErrorLambda implements RequestHandler<SQSEvent, Void> {

    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final String TABLE_NAME = "notification-error-table";

    public SendNotificationsErrorLambda() {
        this.dynamoDbClient = DynamoDbClient.create();
    }

    @Override
    public Void handleRequest(SQSEvent event, Context context) {
        event.getRecords().forEach(message -> {
            try {
                String body = message.getBody();
                JsonNode jsonNode = objectMapper.readTree(body);

                Map<String, AttributeValue> item = new HashMap<>();
                item.put("uuid", AttributeValue.builder().s(UUID.randomUUID().toString()).build());
                item.put("createdAt", AttributeValue.builder().s(Instant.now().toString()).build());

                Map<String, AttributeValue> originalMessageMap = convertJsonToAttributeValueMap(jsonNode);
                item.put("originalMessage", AttributeValue.builder().m(originalMessageMap).build());

                String errorMessage = jsonNode.has("errorMessage")
                        ? jsonNode.get("errorMessage").asText()
                        : "Unknown error";
                item.put("errorMessage", AttributeValue.builder().s(errorMessage).build());

                dynamoDbClient.putItem(PutItemRequest.builder()
                        .tableName(TABLE_NAME)
                        .item(item)
                        .build());

                context.getLogger().log("Error registrado en DynamoDB: " + item.get("uuid").s());

            } catch (Exception e) {
                context.getLogger().log("Error procesando mensaje de DLQ: " + e.getMessage());
            }
        });
        return null;
    }

    private Map<String, AttributeValue> convertJsonToAttributeValueMap(JsonNode jsonNode) {
        Map<String, AttributeValue> map = new HashMap<>();

        jsonNode.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();

            if (value.isObject()) {
                map.put(key, AttributeValue.builder().m(convertJsonToAttributeValueMap(value)).build());
            } else if (value.isArray()) {
                map.put(key, AttributeValue.builder()
                        .l(value.findValuesAsText("")
                                .stream()
                                .map(v -> AttributeValue.builder().s(v).build())
                                .toList())
                        .build());
            } else if (value.isNumber()) {
                map.put(key, AttributeValue.builder().n(value.asText()).build());
            } else if (value.isBoolean()) {
                map.put(key, AttributeValue.builder().bool(value.asBoolean()).build());
            } else {
                map.put(key, AttributeValue.builder().s(value.asText()).build());
            }
        });

        return map;
    }
}
