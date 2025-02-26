/*
 * Licensed to Laurent Broudoux (the "Author") under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Author licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.github.microcks.minion.async.consumer;

import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;

import io.github.microcks.domain.Header;
import io.github.microcks.minion.async.AsyncTestSpecification;

import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.jboss.logging.Logger;

import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An implementation of <code>MessageConsumptionTask</code> that consumes a topic on an Apache Kafka Broker.
 * Endpoint URL should be specified using the following form: <code>kafka://{brokerhost[:port]}/{topic}[?option1=value1&amp;option2=value2]</code>
 * @author laurent
 */
public class KafkaMessageConsumptionTask implements MessageConsumptionTask {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   /** The string for Regular Expression that helps validating acceptable endpoints. */
   public static final String ENDPOINT_PATTERN_STRING = "kafka://(?<brokerUrl>[^:]+(:\\d+)?)/(?<topic>[a-zA-Z0-9-_\\.]+)(\\?(?<options>.+))?";
   /** The Pattern for matching groups within the endpoint regular expression. */
   public static final Pattern ENDPOINT_PATTERN = Pattern.compile(ENDPOINT_PATTERN_STRING);

   /** The endpoint URL option representing schema registry URL. */
   public static final String REGISTRY_URL_OPTION = "registryUrl";
   /** The endpoint URL option representing schema registry username. */
   public static final String REGISTRY_USERNAME_OPTION = "registryUsername";
   /** The endpoint URL option representing schema registry auth credentials source. */
   public static final String REGISTRY_AUTH_CREDENTIALS_SOURCE = "registryAuthCredSource";

   private File trustStore;

   private AsyncTestSpecification specification;

   protected Map<String, String> optionsMap;

   protected KafkaConsumer<String, byte[]> consumer;

   protected KafkaConsumer<String, GenericRecord> avroConsumer;


   /**
    * Create a new consumption task from an Async test specification.
    * @param testSpecification The specification holding endpointURL and timeout.
    */
   public KafkaMessageConsumptionTask(AsyncTestSpecification testSpecification) {
      this.specification = testSpecification;
   }

   /**
    * Convenient static method for checking if this implementation will accept endpoint.
    * @param endpointUrl The endpoint URL to validate
    * @return True if endpointUrl can be used for connecting and consuming on endpoint
    */
   public static boolean acceptEndpoint(String endpointUrl) {
      return endpointUrl != null && endpointUrl.matches(ENDPOINT_PATTERN_STRING);
   }

   @Override
   public List<ConsumedMessage> call() throws Exception {
      if (consumer == null && avroConsumer == null) {
         initializeKafkaConsumer();
      }
      List<ConsumedMessage> messages = new ArrayList<>();

      // Start polling with appropriate consumer for records.
      // Do not forget to close the consumer before returning results.
      if (consumer != null) {
         consumeByteArray(messages);
         consumer.close();
      } else {
         consumeAvro(messages);
         avroConsumer.close();
      }
      return messages;
   }

   /**
    * Close the resources used by this task. Namely the Kafka consumer(s) and
    * the optionally created truststore holding Kafka client SSL credentials.
    * @throws IOException should not happen.
    */
   @Override
   public void close() throws IOException {
      if (consumer != null) {
         consumer.close();
      }
      if (avroConsumer != null) {
         avroConsumer.close();
      }
      if (trustStore != null && trustStore.exists()) {
         trustStore.delete();
      }
   }

   /** Initialize Kafka consumer from built properties and subscribe to target topic. */
   private void initializeKafkaConsumer() {
      Matcher matcher = ENDPOINT_PATTERN.matcher(specification.getEndpointUrl().trim());
      // Call matcher.find() to be able to use named expressions.
      matcher.find();
      String endpointBrokerUrl = matcher.group("brokerUrl");
      String endpointTopic = matcher.group("topic");
      String options = matcher.group("options");

      // Parse options if specified.
      if (options != null && !options.isBlank()) {
         initializeOptionsMap(options);
      }

      Properties props = new Properties();
      props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, endpointBrokerUrl);

      // Generate a unique GroupID for no collision with previous or other consumers.
      props.put(ConsumerConfig.GROUP_ID_CONFIG, specification.getTestResultId() + "-" + System.currentTimeMillis());
      props.put(ConsumerConfig.CLIENT_ID_CONFIG, "microcks-async-minion-test");

      props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

      // Value deserializer depends on schema registry presence.
      if (hasOption(REGISTRY_URL_OPTION)) {
         props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class.getName());
         props.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, optionsMap.get(REGISTRY_URL_OPTION));
         // Configure schema registry credentials if any.
         if (hasOption(REGISTRY_USERNAME_OPTION) || hasOption(REGISTRY_AUTH_CREDENTIALS_SOURCE)) {
            props.put(AbstractKafkaAvroSerDeConfig.USER_INFO_CONFIG, optionsMap.get(REGISTRY_USERNAME_OPTION));
            props.put(AbstractKafkaAvroSerDeConfig.BASIC_AUTH_CREDENTIALS_SOURCE, optionsMap.get(REGISTRY_AUTH_CREDENTIALS_SOURCE));
         }
      } else {
         props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
      }

      // Only retrieve incoming messages and do not persist offset.
      props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
      props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

      if (specification.getSecret() != null && specification.getSecret().getCaCertPem() != null) {
         try {
            // Because Kafka Java client does not support any other sources for SSL configuration,
            // we need to create a Truststore holding the secret certificate and credentials. See below:
            // https://cwiki.apache.org/confluence/display/KAFKA/KIP-486%3A+Support+custom+way+to+load+KeyStore+and+TrustStore
            trustStore = ConsumptionTaskCommons.installBrokerCertificate(specification);

            // Then we have to add SSL specific properties.
            props.put("security.protocol", "SSL");
            props.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, trustStore.getAbsolutePath());
            props.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, ConsumptionTaskCommons.TRUSTSTORE_PASSWORD);
         } catch (Exception e) {
            logger.error("Exception while installing custom truststore: " + e.getMessage());
         }
      }

      // Create the consumer from properties and subscribe to given topic.
      if (hasOption(REGISTRY_URL_OPTION)) {
         avroConsumer = new KafkaConsumer<>(props);
         avroConsumer.subscribe(Arrays.asList(endpointTopic));
      } else {
         consumer = new KafkaConsumer<>(props);
         consumer.subscribe(Arrays.asList(endpointTopic));
      }
   }

   /**
    * Initialize options map from options string found in Endpoint URL.
    * @param options A string of options having the form: option1=value1&amp;option2=value2
    */
   protected void initializeOptionsMap(String options) {
      optionsMap = new HashMap<>();
      String[] keyValuePairs = options.split("&");
      for (String keyValuePair : keyValuePairs) {
         String[] keyValue = keyValuePair.split("=");
         if (keyValue.length > 1) {
            optionsMap.put(keyValue[0], keyValue[1]);
         }
      }
   }

   /**
    * Safe method for checking if an option has been set.
    * @param optionKey Check if that option is available in options map.
    * @return true if option is present, false if undefined.
    */
   protected boolean hasOption(String optionKey) {
      if (optionsMap != null) {
         return optionsMap.containsKey(optionKey);
      }
      return false;
   }

   /** Consume simple byte[] on default consumer. Fill messages array. */
   private void consumeByteArray(List<ConsumedMessage> messages){
      long startTime = System.currentTimeMillis();
      long timeoutTime = startTime + specification.getTimeoutMS();

      while (System.currentTimeMillis() - startTime < specification.getTimeoutMS()) {
         ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(timeoutTime - System.currentTimeMillis()));

         for (ConsumerRecord<String, byte[]> record : records) {
            // Build a ConsumedMessage from Kafka record.
            ConsumedMessage message = new ConsumedMessage();
            message.setReceivedAt(System.currentTimeMillis());
            message.setHeaders(buildHeaders(record.headers()));
            message.setPayload(record.value());
            messages.add(message);
         }
      }
   }

   /** Consumer avro records when connected to registry. Fill messages array. */
   private void consumeAvro(List<ConsumedMessage> messages) {
      long startTime = System.currentTimeMillis();
      long timeoutTime = startTime + specification.getTimeoutMS();

      while (System.currentTimeMillis() - startTime < specification.getTimeoutMS()) {
         ConsumerRecords<String, GenericRecord> records = avroConsumer.poll(Duration.ofMillis(timeoutTime - System.currentTimeMillis()));

         for (ConsumerRecord<String, GenericRecord> record : records) {
            // Build a ConsumedMessage from Kafka record.
            ConsumedMessage message = new ConsumedMessage();
            message.setReceivedAt(System.currentTimeMillis());
            message.setHeaders(buildHeaders(record.headers()));
            message.setPayloadRecord(record.value());
            messages.add(message);
         }
      }
   }

   /** Build set of Microcks headers from Kafka headers. */
   private Set<Header> buildHeaders(Headers headers) {
      if (headers == null || !headers.iterator().hasNext()) {
         return null;
      }
      Set<Header> results = new TreeSet<>();
      Iterator<org.apache.kafka.common.header.Header> headersIterator = headers.iterator();
      while (headersIterator.hasNext()) {
         org.apache.kafka.common.header.Header header = headersIterator.next();
         Header result = new Header();
         result.setName(header.key());
         result.setValues(Stream.of(new String(header.value())).collect(Collectors.toSet()));
      }
      return results;
   }
}
