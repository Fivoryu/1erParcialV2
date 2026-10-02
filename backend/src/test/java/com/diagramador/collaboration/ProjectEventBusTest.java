package com.diagramador.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@SpringBootTest(classes = ProjectEventBusTest.BusTestConfiguration.class,
        properties = "diagrammer.events.heartbeat-ms=50")
class ProjectEventBusTest {

    private final ProjectEventBus eventBus;
    private final List<MvcResult> openStreams = new ArrayList<>();
    private MockMvc mockMvc;
    private SubscriptionProbe probe;

    @Autowired
    ProjectEventBusTest(ProjectEventBus eventBus) {
        this.eventBus = eventBus;
    }

    @BeforeEach
    void setUp() {
        probe = new SubscriptionProbe(eventBus);
        mockMvc = MockMvcBuilders.standaloneSetup(probe).build();
    }

    @AfterEach
    void completeOpenStreams() {
        for (MvcResult result : openStreams) {
            if (result.getRequest().isAsyncStarted()) {
                result.getRequest().getAsyncContext().complete();
            }
        }
        openStreams.clear();
    }

    @Test
    void publishesToBothSubscribersOfOneProjectAndNotAnotherProject() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID otherProjectId = UUID.randomUUID();
        MvcResult first = subscribe(projectId);
        MvcResult second = subscribe(projectId);
        MvcResult otherProject = subscribe(otherProjectId);
        ProjectEvent event = ProjectEvent.of("operation.applied", projectId, Map.of("operationId", "op-123"));

        assertThat(eventBus.subscriberCount(projectId)).isEqualTo(2);
        assertThatNoException().isThrownBy(() -> eventBus.publish(event));

        assertEventReceived(first, event);
        assertEventReceived(second, event);
        assertThat(otherProject.getResponse().getContentAsString())
                .doesNotContain("operation.applied")
                .doesNotContain("\"schemaVersion\":\"1.0\"");
    }

    @Test
    void completionAndDeadSubscriberRemoveTheirEmittersWithoutRemovingOtherSubscribers() throws Exception {
        UUID completedProject = UUID.randomUUID();
        MvcResult completed = subscribe(completedProject);
        subscribe(completedProject);
        assertThat(eventBus.subscriberCount(completedProject)).isEqualTo(2);

        completed.getRequest().getAsyncContext().complete();
        assertThat(eventBus.subscriberCount(completedProject)).isEqualTo(1);

        UUID erroredProject = UUID.randomUUID();
        MvcResult errored = subscribe(erroredProject);
        MvcResult surviving = subscribe(erroredProject);
        assertThat(eventBus.subscriberCount(erroredProject)).isEqualTo(2);

        // A container-level error cannot be produced through MockMvc: completing an emitter with an error
        // here propagates that error to the caller and does NOT invoke the emitter's error callback, so the
        // onError registration is declared as not exercised by this suite. What must hold regardless, and
        // what this asserts, is that a dead subscriber never leaks and never blocks the live ones: the next
        // send removes it (in production the heartbeat guarantees that within one interval), and the other
        // subscriber still receives the event.
        probe.emitter(erroredProject, 0).completeWithError(new IOException("simulated subscriber failure"));
        assertThatThrownBy(() -> mockMvc.perform(asyncDispatch(errored)).andReturn())
                .isInstanceOf(IOException.class);

        ProjectEvent afterError = ProjectEvent.of("operation.applied", erroredProject, Map.of("operationId", "op-123"));
        assertThatNoException().isThrownBy(() -> eventBus.publish(afterError));
        assertThat(eventBus.subscriberCount(erroredProject)).isEqualTo(1);
        assertEventReceived(surviving, afterError);
    }

    @Test
    void serializationFailureDoesNotEscapePublish() throws Exception {
        UUID projectId = UUID.randomUUID();
        subscribe(projectId);
        ProjectEvent event = ProjectEvent.of("operation.applied", projectId,
                Map.of("value", new SelfReferencingPayload()));

        assertThat(eventBus.subscriberCount(projectId)).isEqualTo(1);
        assertThatNoException().isThrownBy(() -> eventBus.publish(event));
        assertThat(eventBus.subscriberCount(projectId)).isZero();
    }

    @Test
    void publishingWithNoSubscribersIsHarmless() {
        ProjectEvent event = ProjectEvent.of("operation.applied", UUID.randomUUID(), Map.of());

        assertThat(eventBus.subscriberCount(event.projectId())).isZero();
        assertThatNoException().isThrownBy(() -> eventBus.publish(event));
    }

    private MvcResult subscribe(UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(get("/test-streams/{projectId}", projectId)
                .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        openStreams.add(result);
        return result;
    }

    private static void assertEventReceived(MvcResult result, ProjectEvent event) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("event:" + event.type())
                .contains("\"schemaVersion\":\"1.0\"")
                .contains("\"type\":\"" + event.type() + "\"")
                .contains("\"projectId\":\"" + event.projectId() + "\"")
                .contains("\"payload\":{\"operationId\":\"op-123\"}");
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ProjectEventBus.class)
    static class BusTestConfiguration {
    }

    public static final class SelfReferencingPayload {
        public SelfReferencingPayload getSelf() {
            return this;
        }
    }

    @RestController
    static class SubscriptionProbe {

        private final ProjectEventBus eventBus;
        private final ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

        SubscriptionProbe(ProjectEventBus eventBus) {
            this.eventBus = eventBus;
        }

        @GetMapping(path = "/test-streams/{projectId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter subscribe(@PathVariable UUID projectId) {
            SseEmitter emitter = eventBus.subscribe(projectId);
            emitters.computeIfAbsent(projectId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
            return emitter;
        }

        SseEmitter emitter(UUID projectId, int index) {
            return emitters.get(projectId).get(index);
        }
    }
}
