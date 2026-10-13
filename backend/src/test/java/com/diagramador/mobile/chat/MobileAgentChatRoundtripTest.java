package com.diagramador.mobile.chat;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.diagramador.identity.*;
import com.diagramador.domain.Deployment;
import com.diagramador.persistence.DeploymentRepository;
import com.diagramador.generation.*;
import com.fasterxml.jackson.databind.ObjectMapper;
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class MobileAgentChatRoundtripTest extends MobileAgentChatPersistenceTest {
 UUID fixture=UUID.randomUUID(), chat=UUID.randomUUID(); String hash="a".repeat(64);
 MobileAgentChatService service; CurrentUserContext users; @org.springframework.beans.factory.annotation.Autowired org.springframework.transaction.PlatformTransactionManager transactions;
 @BeforeEach void fixture() {
  sql("insert into diagrammer.user_account(user_id,external_subject,display_name,username,password_hash) values (?1,?2,'Fixture',?2,'fixture-not-login')",fixture,fixture.toString());
  sql("insert into diagrammer.project(project_id,owner_user_id,name) values (?1,?1,'Fixture')",fixture);
  sql("insert into diagrammer.uml_model(model_id,project_id,name) values (?1,?1,'Fixture')",fixture);
  sql("insert into diagrammer.model_version(version_id,model_id,revision,state_hash) values (?1,?1,1,?2)",fixture,hash);
  sql("insert into diagrammer.generation_target(target_id,model_id,target_key,name,database_name) values (?1,?1,'fixture','Fixture','fixture')",fixture);
  sql("insert into diagrammer.generation_profile(profile_id,project_id,profile_key,name,configuration_hash) values (?1,?1,'fixture','Fixture',?2)",fixture,hash);
  sql("insert into diagrammer.template_version(template_id,profile_id,label,content_hash) values (?1,?1,'fixture',?2)",fixture,hash);
  sql("insert into diagrammer.generation_run(run_id,model_id,version_id,target_id,target_config_revision,profile_id,template_id,input_snapshot_hash,artifact_set_hash) values (?1,?1,?1,?1,1,?1,?1,?2,?2)",fixture,hash);
  sql("insert into diagrammer.deployment(deployment_id,run_id,target_id,http_host_port,postgres_host_port) values (?1,?1,?1,19001,19002)",fixture);
  users=new CurrentUserContext();users.set(new CurrentUser(fixture,"fixture","Fixture"));
  var deployments=mock(DeploymentRepository.class); var target=mock(Deployment.class,RETURNS_DEEP_STUBS);
  when(target.getRun().getModel().getProject().getId()).thenReturn(fixture);
  when(target.getRun().getArtifactSetHash()).thenReturn(hash);
  when(target.getRun().getId()).thenReturn(fixture);
  when(deployments.findById(fixture)).thenReturn(Optional.of(target));
  var artifacts=mock(GenerationArtifactService.class);
  var bundle=mock(GenerationArtifactBundleBuilder.GeneratedArtifactBundle.class);
  when(bundle.files()).thenReturn(Map.of(GenerationArtifactBundleBuilder.OPENAPI_PATH,"{\"openapi\":\"3.0.3\",\"paths\":{}}"));
  when(artifacts.downloadComplete(any(),any())).thenReturn(Optional.of(bundle));
  service=new MobileAgentChatService(conversations,messages,users,mock(ProjectAuthorizationService.class),deployments,artifacts,mock(InputSnapshotFactory.class),new ObjectMapper(),em);
  var create=new MobileAgentChatResources.ConversationRequest(chat,"Fixture",hash);
  assertThat(service.create(fixture,fixture,create).id()).isEqualTo(chat);
  em.flush(); em.clear();
  assertThat(service.create(fixture,fixture,create).id()).isEqualTo(chat);
 }
 @ParameterizedTest @ValueSource(strings={"2026-10-13T12:00:00.123456Z","2026-10-13T13:00:00.123456+01:00","2026-10-13T12:00:00.123456789Z"})
 void exactPublicAppendRetryAfterDatabaseRoundtrip(String timestamp)throws Exception {
  var mapper=new ObjectMapper(); var metadata=mapper.readTree("{\"a\":1,\"nested\":{\"b\":2}}");
  var request=new MobileAgentChatResources.MessageRequest(UUID.randomUUID(),"user","Same message",OffsetDateTime.parse(timestamp),metadata);
  var first=service.append(fixture,fixture,chat,request);em.flush();em.clear();
  var persisted=messages.findById(request.id()).orElseThrow();
  assertThat(persisted.clientCreatedAt).isEqualTo(request.createdAt().toString());
  long count=messages.count();String before=em.createNativeQuery("select md5(string_agg(row_to_json(m)::text,',' order by message_id)) from diagrammer.agent_message m").getSingleResult().toString();
  try {
   var retry=service.append(fixture,fixture,chat,request);
   assertThat(retry).isEqualTo(first);assertThat(retry.sequence()).isEqualTo(1);
   var changed=new MobileAgentChatResources.MessageRequest(request.id(),"user","Changed",request.createdAt(),metadata);
   assertThatThrownBy(()->service.append(fixture,fixture,chat,changed)).isInstanceOf(com.diagramador.api.ApiException.class);
  } finally {
   assertThat(messages.count()).isEqualTo(count);
   assertThat(em.createNativeQuery("select md5(string_agg(row_to_json(m)::text,',' order by message_id)) from diagrammer.agent_message m").getSingleResult()).isEqualTo(before);
  }
 }
 @org.junit.jupiter.api.Test void absentCreateAndPessimisticAppendSerialize() throws Exception {
  org.springframework.test.context.transaction.TestTransaction.flagForCommit();
  org.springframework.test.context.transaction.TestTransaction.end();
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
  UUID shared=UUID.randomUUID();
  try {
   var start=new java.util.concurrent.CountDownLatch(1);
   java.util.concurrent.Callable<java.util.UUID> create=()->{users.set(new CurrentUser(fixture,"fixture","Fixture"));start.await();return tx.execute(status->service.create(fixture,fixture,new MobileAgentChatResources.ConversationRequest(shared,"Concurrent",hash)).id());};
   var one=pool.submit(create);var two=pool.submit(create);start.countDown();
   assertThat(one.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(shared);
   assertThat(two.get(20,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(shared);
   java.util.concurrent.Callable<Long> append=()->{users.set(new CurrentUser(fixture,"fixture","Fixture"));return tx.execute(status->service.append(fixture,fixture,shared,new MobileAgentChatResources.MessageRequest(UUID.randomUUID(),"user","Concurrent",null,null)).sequence());};
   var a=pool.submit(append);var b=pool.submit(append);
   assertThat(java.util.List.of(a.get(20,java.util.concurrent.TimeUnit.SECONDS),b.get(20,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(1L,2L);
   tx.execute(status->{assertThat(messages.findByConversationIdAndSequenceGreaterThanOrderBySequenceAsc(shared,0,org.springframework.data.domain.PageRequest.of(0,10))).hasSize(2);return null;});
  } finally {pool.shutdownNow();}
 }
 void sql(String query,Object... values){var q=em.createNativeQuery(query);for(int i=0;i<values.length;i++)q.setParameter(i+1,values[i]);q.executeUpdate();}
}
