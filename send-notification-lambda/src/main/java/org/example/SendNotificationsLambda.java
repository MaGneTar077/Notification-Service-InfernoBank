package org.example;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.SQSBatchResponse;
import com.amazonaws.services.lambda.runtime.events.SQSEvent;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.*;

public class SendNotificationsLambda implements RequestHandler<SQSEvent, SQSBatchResponse> {

    private final AmazonS3 s3 = AmazonS3ClientBuilder.defaultClient();
    private final AmazonDynamoDB dynamo = AmazonDynamoDBClientBuilder.defaultClient();
    private final AmazonSQS sqs = AmazonSQSClientBuilder.defaultClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String S3_BUCKET = System.getenv("TEMPLATES_BUCKET");
    private static final String NOTIFICATION_TABLE = System.getenv("NOTIFICATIONS_TABLE");
    private static final String DLQ_URL = System.getenv("NOTIFICATION_DLQ_URL");

    @Override
    public SQSBatchResponse handleRequest(SQSEvent event, Context context) {
        LambdaLogger logger = context.getLogger();
        List<SQSBatchResponse.BatchItemFailure> failures = new ArrayList<>();

        for (SQSEvent.SQSMessage msg : event.getRecords()) {
            String msgId = msg.getMessageId();
            try {
                logger.log("Received message id=" + msgId + " body=" + msg.getBody());

                Map<String, Object> message = objectMapper.readValue(msg.getBody(), Map.class);
                String type = (String) message.get("type");
                Map<String, Object> data = (Map<String, Object>) message.get("data");
                if (type == null || data == null) {
                    throw new RuntimeException("Invalid message format: missing type or data");
                }

                String templateKey = "templates/" + type + ".html";
                String templateContent = loadTemplateFromS3(templateKey, logger);

                String rendered = renderTemplate(templateContent, data, logger);

                String notificationId = UUID.randomUUID().toString();
                String createdAt = Instant.now().toString();

                Map<String, AttributeValue> item = new HashMap<>();
                item.put("uuid", new AttributeValue(notificationId));
                item.put("createdAt", new AttributeValue(createdAt));
                item.put("type", new AttributeValue(type));
                item.put("payload", new AttributeValue(objectMapper.writeValueAsString(data)));
                item.put("rendered", new AttributeValue(rendered));

                PutItemRequest putReq = new PutItemRequest()
                        .withTableName(NOTIFICATION_TABLE)
                        .withItem(item);

                dynamo.putItem(putReq);
                logger.log("Saved notification in DynamoDB id=" + notificationId);

                logger.log("Rendered notification body: " + rendered);

            } catch (Exception e) {
                logger.log("Error processing message id=" + msgId + " -> " + e.getMessage());

                try {
                    if (DLQ_URL != null && !DLQ_URL.isEmpty()) {
                        sqs.sendMessage(new SendMessageRequest().withQueueUrl(DLQ_URL).withMessageBody(msg.getBody()));
                        logger.log("Forwarded original message to DLQ: " + DLQ_URL);
                    }
                } catch (Exception dlqEx) {
                    logger.log("Failed sending to DLQ: " + dlqEx.getMessage());
                }

                failures.add(new SQSBatchResponse.BatchItemFailure(msgId));
            }
        }

        return new SQSBatchResponse(failures);
    }

    private String loadTemplateFromS3(String key, LambdaLogger logger) {
        try {
            if (S3_BUCKET == null || S3_BUCKET.isEmpty()) {
                logger.log("No S3 bucket configured (TEMPLATES_BUCKET). Using fallback empty template.");
                return "";
            }
            if (!s3.doesObjectExist(S3_BUCKET, key)) {
                logger.log("Template not found in S3: " + key + " — returning empty template");
                return "";
            }
            S3Object obj = s3.getObject(S3_BUCKET, key);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(obj.getObjectContent()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
                return sb.toString();
            }
        } catch (Exception e) {
            logger.log("Error loading template from S3: " + e.getMessage());
            return "";
        }
    }

    private String renderTemplate(String template, Map<String, Object> data, LambdaLogger logger) {
        if (template == null) template = "";
        String result = template;
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String placeholder = "{{" + entry.getKey() + "}}";
            String value = entry.getValue() == null ? "" : entry.getValue().toString();
            result = result.replace(placeholder, value);
        }

        if (!result.contains("{{")) {
            return result;
        } else {
            logger.log("Template still contains placeholders after replacement.");
            return result;
        }
    }

}
