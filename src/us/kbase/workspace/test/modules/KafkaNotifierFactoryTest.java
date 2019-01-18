package us.kbase.workspace.test.modules;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.junit.Test;

import com.google.common.collect.ImmutableMap;

import us.kbase.common.test.MapBuilder;
import us.kbase.common.test.TestCommon;
import us.kbase.workspace.database.ObjectInformation;
import us.kbase.workspace.database.ResolvedWorkspaceID;
import us.kbase.workspace.database.UncheckedUserMetadata;
import us.kbase.workspace.database.WorkspaceUser;
import us.kbase.workspace.database.WorkspaceUserMetadata;
import us.kbase.workspace.listener.ListenerInitializationException;
import us.kbase.workspace.listener.WorkspaceEventListener;
import us.kbase.workspace.modules.KafkaNotifierFactory;

public class KafkaNotifierFactoryTest {
	
	private static TestMocks initTestMocks(final String topic, final String bootstrapServers)
			throws Exception {
		@SuppressWarnings("unchecked")
		final KafkaProducer<String, Map<String, Object>> client = mock(KafkaProducer.class);
		final WorkspaceEventListener notis = getKafkaNotifier(topic, bootstrapServers, client);
		return new TestMocks(client, notis);
	}

	private static WorkspaceEventListener getKafkaNotifier(
			final String topic,
			final String bootstrapServers,
			final KafkaProducer<String, Map<String, Object>> client)
			throws Exception {
		final Class<?> inner = KafkaNotifierFactory.class.getDeclaredClasses()[0];
		final Constructor<?> con = inner.getDeclaredConstructor(
				String.class, String.class, KafkaProducer.class);
		con.setAccessible(true);
		final WorkspaceEventListener notis = (WorkspaceEventListener) con.newInstance(
				topic, bootstrapServers, client);
		return notis;
	}
	
	private static final class TestMocks {
		private KafkaProducer<String, Map<String, Object>> client;
		private WorkspaceEventListener notis;

		private TestMocks(
				final KafkaProducer<String, Map<String, Object>> client,
				final WorkspaceEventListener notis) {
			this.client = client;
			this.notis = notis;
		}
	}
	
	@Test
	public void saveObject() throws Exception {
		final TestMocks mocks = initTestMocks("mytopic2", "localhost:9081");
		
		@SuppressWarnings("unchecked")
		final Future<RecordMetadata> fut = mock(Future.class);
		
		when(mocks.client.send(new ProducerRecord<String, Map<String,Object>>("mytopic2",
				MapBuilder.<String, Object>newHashMap()
						.with("user", "user1")
						.with("wsid", 22L)
						.with("objid", 6L)
						.with("ver", 3)
						.with("evtype", "NEW_VERSION")
						.with("objtype", "Foo.Bar-2.1")
						.with("time", 10000L)
						.build())))
				.thenReturn(fut);

		mocks.notis.saveObject(new ObjectInformation(
				6L,
				"foo",
				"Foo.Bar-2.1",
				new Date(10000),
				3,
				new WorkspaceUser("user1"),
				new ResolvedWorkspaceID(22L, "bar", false, false),
				"chksum",
				30L,
				new UncheckedUserMetadata((WorkspaceUserMetadata) null)),
				true);
		
		verify(mocks.client).partitionsFor("mytopic2");
		verify(fut).get(35000, TimeUnit.MILLISECONDS);
	}
	
	/* The post method is the same for all the notification calls, so we don't repeat each
	 * post failure test for each call.
	 * We do test with different methods for each failure mode though.
	 */
	
	//TODO NOW use different calls to test post fails
	
	@Test
	public void postFailInterrupted() throws Exception {
		final TestMocks mocks = initTestMocks("mytopic3", "localhost:9081");
		@SuppressWarnings("unchecked")
		final Future<RecordMetadata> fut = mock(Future.class);
		
		when(mocks.client.send(new ProducerRecord<String, Map<String,Object>>("mytopic3",
				MapBuilder.<String, Object>newHashMap()
						.with("user", "user1")
						.with("wsid", 22L)
						.with("objid", 6L)
						.with("ver", 3)
						.with("evtype", "NEW_VERSION")
						.with("objtype", "Foo.Bar-2.1")
						.with("time", 10000L)
						.build())))
				.thenReturn(fut);
			
		when(fut.get(35000, TimeUnit.MILLISECONDS)).thenThrow(new InterruptedException("oopsie"));
		
		try {
			mocks.notis.saveObject(new ObjectInformation(
					6L,
					"foo",
					"Foo.Bar-2.1",
					new Date(10000),
					3,
					new WorkspaceUser("user1"),
					new ResolvedWorkspaceID(22L, "bar", false, false),
					"chksum",
					30L,
					new UncheckedUserMetadata((WorkspaceUserMetadata) null)),
					true);
			fail("expected exception");
		} catch (Exception got) {
			TestCommon.assertExceptionCorrect(got,
					new RuntimeException("Failed sending notification to Kafka: oopsie"));
		}
	}
	
	@Test
	public void postFailTimeout() throws Exception {
		final TestMocks mocks = initTestMocks("mytopic3", "localhost:9081");
		@SuppressWarnings("unchecked")
		final Future<RecordMetadata> fut = mock(Future.class);
		
		when(mocks.client.send(new ProducerRecord<String, Map<String,Object>>("mytopic3",
				MapBuilder.<String, Object>newHashMap()
						.with("user", "user1")
						.with("wsid", 22L)
						.with("objid", 6L)
						.with("ver", 3)
						.with("evtype", "NEW_VERSION")
						.with("objtype", "Foo.Bar-2.1")
						.with("time", 10000L)
						.build())))
				.thenReturn(fut);
			
		when(fut.get(35000, TimeUnit.MILLISECONDS)).thenThrow(new TimeoutException("time up"));
		
		try {
			mocks.notis.saveObject(new ObjectInformation(
					6L,
					"foo",
					"Foo.Bar-2.1",
					new Date(10000),
					3,
					new WorkspaceUser("user1"),
					new ResolvedWorkspaceID(22L, "bar", false, false),
					"chksum",
					30L,
					new UncheckedUserMetadata((WorkspaceUserMetadata) null)),
					true);
			fail("expected exception");
		} catch (Exception got) {
			TestCommon.assertExceptionCorrect(got,
					new RuntimeException("Failed sending notification to Kafka: time up"));
		}
	}
	
	@Test
	public void postFailExecutionException() throws Exception {
		final TestMocks mocks = initTestMocks("mytopic2", "localhost:9081");
		
		@SuppressWarnings("unchecked")
		final Future<RecordMetadata> fut = mock(Future.class);
		
		when(mocks.client.send(new ProducerRecord<String, Map<String,Object>>("mytopic2",
				MapBuilder.<String, Object>newHashMap()
						.with("user", "user1")
						.with("wsid", 22L)
						.with("objid", 6L)
						.with("ver", 3)
						.with("evtype", "NEW_VERSION")
						.with("objtype", "Foo.Bar-2.1")
						.with("time", 10000L)
						.build())))
				.thenReturn(fut);

		when(fut.get(35000, TimeUnit.MILLISECONDS)).thenThrow(
				new ExecutionException("not this one", new IllegalStateException("this one")));
		
		try {
			mocks.notis.saveObject(new ObjectInformation(
					6L,
					"foo",
					"Foo.Bar-2.1",
					new Date(10000),
					3,
					new WorkspaceUser("user1"),
					new ResolvedWorkspaceID(22L, "bar", false, false),
					"chksum",
					30L,
					new UncheckedUserMetadata((WorkspaceUserMetadata) null)),
					true);
			fail("expected exception");
		} catch (Exception got) {
			TestCommon.assertExceptionCorrect(got, new RuntimeException(
					"Failed sending notification to Kafka: this one"));
		}
	}
	

	/* Kafka notifier constructor fail tests */
	
	@Test
	public void constructBadTopicFail() throws Exception {
		badTopicFail(null, new IllegalArgumentException(
				"Kafka topic cannot be null or whitespace only"));
		badTopicFail("   \t    \n    ", new IllegalArgumentException(
				"Kafka topic cannot be null or whitespace only"));
		badTopicFail("   \t    \n    ", new IllegalArgumentException(
				"Kafka topic cannot be null or whitespace only"));
		badTopicFail(TestCommon.LONG1001.substring(0, 250),
				new IllegalArgumentException("Kafka topic size greater than limit 249"));
		badTopicFail("  topic.whee ", new ListenerInitializationException(
				"Illegal character in Kafka topic topic.whee: ."));
		badTopicFail("  topic_whee ", new ListenerInitializationException(
				"Illegal character in Kafka topic topic_whee: _"));
		badTopicFail("  topic*whee ", new ListenerInitializationException(
				"Illegal character in Kafka topic topic*whee: *"));
	}
	
	private void badTopicFail(final String topic, final Exception expected) throws Exception {
		try {
			initTestMocks(topic, "foo");
			fail("expected exception");
		} catch (InvocationTargetException got) {
			TestCommon.assertExceptionCorrect(got.getCause(), expected);
		}
	}
	
	@Test
	public void constructNullClientFail() throws Exception {
		try {
			getKafkaNotifier("foo", "foo", null);
			fail("expected exception");
		} catch (InvocationTargetException got) {
			TestCommon.assertExceptionCorrect(got.getCause(), new NullPointerException("client"));
		}
		
	}
	
	@Test
	public void constructKafkaConnectFail() throws Exception {
		@SuppressWarnings("unchecked")
		final KafkaProducer<String, Map<String, Object>> client = mock(KafkaProducer.class);
		when(client.partitionsFor("topicalointment")).thenThrow(new KafkaException("well, darn"));
		
		try {
			getKafkaNotifier("topicalointment", "localhost:5467", client);
			fail("expected exception");
		} catch (InvocationTargetException got) {
			TestCommon.assertExceptionCorrect(got.getCause(), new ListenerInitializationException(
					"Could not reach Kafka instance at localhost:5467"));
		}
		verify(client).close(0, TimeUnit.MILLISECONDS);
	}
	
	@Test
	public void mapSerializer() throws Exception {
		final KafkaNotifierFactory.MapSerializer mapSerializer =
				new KafkaNotifierFactory.MapSerializer();
		
		mapSerializer.configure(null, false); // does nothing;
		mapSerializer.close(); // does nothing
		
		final byte[] res = mapSerializer.serialize("ignored", ImmutableMap.of("foo", "bar"));
		
		assertThat("incorrect serialization", new String(res), is("{\"foo\":\"bar\"}"));
	}
	
	@Test
	public void mapSerializerFailNull() throws Exception {
		mapSerializerFail(null, new NullPointerException("data"));
	}

	@Test
	public void mapSerializerFailUnserializable() throws Exception {
		final String e = "Unserializable data sent to Kafka: No serializer found for " +
				"class java.io.ByteArrayOutputStream and no properties discovered to " +
				"create BeanSerializer (to avoid exception, disable " +
				"SerializationFeature.FAIL_ON_EMPTY_BEANS) ) (through reference chain: " +
				"com.google.common.collect.SingletonImmutableBiMap[\"foo\"])";
		mapSerializerFail(ImmutableMap.of("foo", new ByteArrayOutputStream()),
				new RuntimeException(e));
	}
	
	@SuppressWarnings("resource")
	private void mapSerializerFail(final Map<String, Object> data, final Exception expected) {
		try {
			new KafkaNotifierFactory.MapSerializer().serialize("ignored", data);
			fail("expected exception");
		} catch (Exception got) {
			TestCommon.assertExceptionCorrect(got, expected);
		}
	}
	
	/* Tests for building the notifier with the factory. Can really only test failure
	 * scenarios in unit tests.
	 */

	@Test
	public void getNotifierFailNull() throws Exception {
		getNotifierFail(null, new NullPointerException("cfg"));
	}
	
	@Test
	public void getNotifierFailBadBootstrapServer() throws Exception {
		final Map<String, String> c = new HashMap<>();
		c.put("boostrap.servers.wrong", null);
		getNotifierFail(c, new IllegalArgumentException(
				"Kafka bootstrap.servers cannot be null or whitespace only"));
		c.put("boostrap.servers", null);
		getNotifierFail(c, new IllegalArgumentException(
				"Kafka bootstrap.servers cannot be null or whitespace only"));
		c.put("boostrap.servers", "   \t      ");
		getNotifierFail(c, new IllegalArgumentException(
				"Kafka bootstrap.servers cannot be null or whitespace only"));
	}
	
	private void getNotifierFail(final Map<String, String> config, final Exception expected) {
		try {
			new KafkaNotifierFactory().configure(config);
			fail("expected exception");
		} catch (Exception got) {
			TestCommon.assertExceptionCorrect(got, expected);
		}
	}
	
}
