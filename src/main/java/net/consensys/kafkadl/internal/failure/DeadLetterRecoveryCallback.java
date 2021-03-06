package net.consensys.kafkadl.internal.failure;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.consensys.kafkadl.handler.DeadLetterRetriesExhaustedHandler;
import net.consensys.kafkadl.internal.DeadLetterSettings;
import net.consensys.kafkadl.internal.DeadLetterTopicNameConvention;
import net.consensys.kafkadl.internal.KafkaProperties;
import net.consensys.kafkadl.internal.forwarder.ErrorTopicForwarder;
import net.consensys.kafkadl.internal.util.JSON;
import net.consensys.kafkadl.message.ReflectionRetryableMessage;
import net.consensys.kafkadl.message.RetryableMessage;
import net.consensys.kafkadl.util.AnnotationUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.retry.RecoveryCallback;
import org.springframework.retry.RetryContext;

import java.util.List;
import java.util.Optional;

@AllArgsConstructor
@Slf4j
public class DeadLetterRecoveryCallback implements RecoveryCallback {

    private static final Logger LOG = LoggerFactory.getLogger(DeadLetterRecoveryCallback.class);

    private KafkaTemplate kafkaTemplate;
    private DeadLetterTopicNameConvention deadLetterConvention;
    private List<DeadLetterRetriesExhaustedHandler> retriesExhaustedHandlers;
    private ErrorTopicForwarder errorTopicForwarder;
    private KafkaProperties kafkaProperties;
    private DeadLetterSettings deadLetterSettings;

    @Override
    public Object recover(RetryContext context) throws Exception {
        final ConsumerRecord record = (ConsumerRecord) context.getAttribute("record");

        if (deadLetterSettings.isAnnotatedMessages()
                && !AnnotationUtils.isDeadLetterMessageAnnotated(record.value())) {
            log.debug("Message is not @DeadLetterMessage annotated, ignoring");
            executeRetriesExhaustedHandlers(context);
            return null;
        }

        getRetryableMessage(record).ifPresent(message -> {
            if (hasExhaustedRetries(message)) {
                LOG.error(String.format("Retries exhausted for message: %s", JSON.stringify(message)));

                executeRetriesExhaustedHandlers(context);

                errorTopicForwarder.forward(record);
            } else {

                final String key = record.key() != null ? record.key().toString() : null;

                LOG.error(String.format("Failed to process message: %s",
                        JSON.stringify(record.value())), context.getLastThrowable());
                LOG.info("Sending to dead letter topic");
                kafkaTemplate.send(deadLetterConvention.getDeadLetterTopicName(record.topic()), key, record.value());
            }
        });

        return null;
    }

    private Optional<RetryableMessage> getRetryableMessage(ConsumerRecord record) {
        final Object recordValue = record.value();

        if (recordValue instanceof RetryableMessage) {
            return Optional.of((RetryableMessage) recordValue);
        }

        if (ReflectionRetryableMessage.isSupported(recordValue)) {
            return Optional.of(new ReflectionRetryableMessage(recordValue));
        }

        return Optional.empty();
    }

    private boolean hasExhaustedRetries(RetryableMessage message) {
        return message.getRetries() >= getMaxRetries();
    }

    private int getMaxRetries() {
        return kafkaProperties.getDeadLetterTopicRetries();
    }

    private void executeRetriesExhaustedHandlers(RetryContext context) {
        retriesExhaustedHandlers.forEach(handler -> handler.onFailure(context));
    }
}
