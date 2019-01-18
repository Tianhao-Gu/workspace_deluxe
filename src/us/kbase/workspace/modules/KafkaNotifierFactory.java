package us.kbase.workspace.modules;

import static java.util.Objects.requireNonNull;
import static us.kbase.workspace.database.Util.checkString;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;

import us.kbase.workspace.database.ObjectInformation;
import us.kbase.workspace.database.Permission;
import us.kbase.workspace.database.WorkspaceUser;
import us.kbase.workspace.listener.ListenerInitializationException;
import us.kbase.workspace.listener.WorkspaceEventListener;
import us.kbase.workspace.listener.WorkspaceEventListenerFactory;

// TODO NOW add example string once all fields are done
/** A workspace listener that sends workspace events to Kafka as JSON strings.
 * 
 * The listener requires two configuration arguments:
 * topic - the topic to which the listener will submit events. The listener requires the topic
 * name to consist of ASCII alphanumeric values and the hyphen to avoid Kafka issues around
 * ambiguity between period and underscore values. 
 * bootstrap.servers - the Kafka bootstrap servers.
 * 
 * The listener is configured to require a full write to all the replicates before a call
 * to Kafka returns, and if a write fails, an exception is thrown in the thread that called
 * the listener.
 * 
 * @author gaprice@lbl.gov
 *
 */
public class KafkaNotifierFactory implements WorkspaceEventListenerFactory {

		// TODO DOCS
		// TODO TEST integration tests w/ just client & also full group server
		
		/* This implementation does things that slow down the send operation but improve
		 * reliability and user messaging:
		 * 1) Require full write to replicates before Kafka returns
		 * 2) Wait for the return and check it worked. If not, throw an exception *in the calling
		 * thread*. Thus the user is notified if something goes wrong.
		 * 
		 * If this turns out to be a bad plan, we may need to relax those requirements.
		 * 
		 * To improve reliability further, we'd need persistent storage of unsent messages.
		 */
		
		private static final String KAFKA = "Kafka";
		private static final String KCFG_BOOSTRAP_SERVERS = "bootstrap.servers";
		// may want to split events into different topics
		private static final String TOPIC = "topic";
		private static final String KAFKA_WS_TOPIC = KAFKA + " " + TOPIC;
		
		private static final String NEW_OBJECT_VER = "NEW_VERSION";
//		private static final String COPY_OBJECT = "COPY_ALL_VERSIONS";
//		private static final String CLONED_WORKSPACE = "CLONE_WORKSPACE";
//		private static final String RENAME_OBJECT = "RENAME_ALL_VERSIONS";
//		private static final String DELETE_OBJECT = "DELETE_ALL_VERSIONS";
//		private static final String UNDELETE_OBJECT = "UNDELETE_ALL_VERSIONS";
//		private static final String DELETE_WS = "DELETE_ACCESS_GROUP";
//		private static final String SET_GLOBAL_READ = "PUBLISH_ACCESS_GROUP";
//		private static final String REMOVE_GLOBAL_READ = "UNPUBLISH_ACCESS_GROUP";
		
		
		// https://stackoverflow.com/questions/37062904/what-are-apache-kafka-topic-name-limitations
		// Don't include . and _ because of
		// https://github.com/mcollina/ascoltatori/issues/165#issuecomment-267314016
		private final static Pattern INVALID_TOPIC_CHARS = Pattern.compile("[^a-zA-Z0-9-]+");
	
	@Override
	public WorkspaceEventListener configure(final Map<String, String> cfg)
			throws ListenerInitializationException {
		requireNonNull(cfg, "cfg");
		//TODO KAFKA support other config options (ssl etc). Unfortunately will have to parse each key individually as different types are required.
		final Map<String, Object> kcfg = new HashMap<>();
		final String topic = (String) cfg.get(TOPIC);
		final String bootstrapServers = cfg.get(KCFG_BOOSTRAP_SERVERS);
		checkString(bootstrapServers, KAFKA + " " + KCFG_BOOSTRAP_SERVERS);
		// maybe make this config accessible in the factory so it can be tested in integration tests
		kcfg.put(KCFG_BOOSTRAP_SERVERS, bootstrapServers);
		kcfg.put("acks", "all");
		kcfg.put("enable.idempotence", true);
		kcfg.put("delivery.timeout.ms", 30000);
		return new KafkaNotifier(
				topic,
				bootstrapServers,
				new KafkaProducer<>(kcfg, new StringSerializer(), new MapSerializer()));
	}

	/** A Kafka JSON serializer for arbitrary maps. Requires no configuration. The topic
	 * argument in the {@link #serialize(String, Map) method is ignored.
	 * @author gaprice@lbl.gov
	 *
	 */
	public static class MapSerializer implements Serializer<Map<String, Object>> {

		private static final ObjectMapper MAPPER = new ObjectMapper();
		
		/** Create the serializer. */
		public MapSerializer() {}
		
		@Override
		public void close() {
			// nothing to do;
		}

		@Override
		public void configure(Map<String, ?> arg0, boolean arg1) {
			// nothing to do
		}

		@Override
		public byte[] serialize(final String topic, final Map<String, Object> data) {
			try {
				return MAPPER.writeValueAsBytes(requireNonNull(data, "data"));
			} catch (JsonProcessingException e) {
				throw new RuntimeException("Unserializable data sent to Kafka: " + e.getMessage(),
						e);
			}
		}
	}
	
	private static class KafkaNotifier implements WorkspaceEventListener {
		
		private final String topic;
		private final KafkaProducer<String, Map<String, Object>> client;
		
		// constructor is here to allow for unit tests
		private KafkaNotifier(
				final String topic,
				final String bootstrapServers,
				final KafkaProducer<String, Map<String, Object>> client)
				throws ListenerInitializationException {
			this.topic = checkString(topic, KAFKA_WS_TOPIC, 249);
			final Matcher m = INVALID_TOPIC_CHARS.matcher(this.topic);
			if (m.find()) {
				throw new ListenerInitializationException(String.format(
						"Illegal character in %s %s: %s",
						KAFKA_WS_TOPIC, this.topic, m.group()));
			}
			this.client = requireNonNull(client, "client");
			try {
				client.partitionsFor(this.topic); // check kafka is up
			} catch (KafkaException e) {
				// TODO CODE this blocks forever, needs 2.2.0 for a fix.
				// https://issues.apache.org/jira/browse/KAFKA-5503
				client.close(0, TimeUnit.MILLISECONDS);
				// might want a notifier exception here
				throw new ListenerInitializationException("Could not reach Kafka instance at " +
						bootstrapServers);
			}
		}
		
		private void post(final Map<String, Object> message) {
			final Future<RecordMetadata> res = client.send(new ProducerRecord<>(topic, message));
			try {
				res.get(35000, TimeUnit.MILLISECONDS);
			} catch (InterruptedException | TimeoutException e) {
				throw new RuntimeException("Failed sending notification to Kafka: " +
						e.getMessage(), e);
			} catch (ExecutionException e) {
				throw new RuntimeException("Failed sending notification to Kafka: " +
						e.getCause().getMessage(), e.getCause());
			}
		}

		@Override
		public void createWorkspace(long id, Instant time) {
			// no action
		}

		@Override
		public void cloneWorkspace(long id, boolean isPublic, Instant time) {
			// TODO Auto-generated method stub
		}

		@Override
		public void setWorkspaceMetadata(long id, Instant time) {
			// no action
		}

		@Override
		public void lockWorkspace(long id, Instant time) {
			// no action
		}

		@Override
		public void renameWorkspace(long id, String newname, Instant time) {
			// no action
		}

		@Override
		public void setGlobalPermission(long id, Permission permission, Instant time) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void setPermissions(long id, Permission permission, List<WorkspaceUser> users, Instant time) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void setWorkspaceDescription(long id, Instant time) {
			// no action
		}

		@Override
		public void setWorkspaceOwner(long id, WorkspaceUser newUser, Optional<String> newName, Instant time) {
			// no action
		}

		@Override
		public void setWorkspaceDeleted(long id, boolean delete, long maxObjectID, Instant time) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void renameObject(long workspaceId, long objectId, String newName, Instant time) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void revertObject(ObjectInformation object, boolean isPublic) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void setObjectDeleted(long workspaceId, long objectId, boolean delete, Instant time) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void copyObject(ObjectInformation object, boolean isPublic) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void copyObject(long workspaceId, long objectId, int latestVersion, Instant time, boolean isPublic) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public void saveObject(final ObjectInformation oi, final boolean isPublic) {
			newEvent(oi.getSavedBy(), oi.getWorkspaceId(), oi.getObjectId(), oi.getVersion(),
					oi.getTypeString(), NEW_OBJECT_VER, oi.getSavedDate().toInstant());
		}
		
		private void newEvent(
				final WorkspaceUser user,
				final long workspaceId,
				final Long objectId,
				final Integer version,
				final String type,
				final String eventType,
				final Instant time) {
			
			final Map<String, Object> dobj = new HashMap<>();
			dobj.put("user", user.getUser());
			dobj.put("wsid", workspaceId);
			dobj.put("objid", objectId);
			dobj.put("ver", version);
			dobj.put("time", time.toEpochMilli());
			dobj.put("evtype", eventType);
			dobj.put("objtype", type);
			post(dobj);
		}
		
	}
	
}
