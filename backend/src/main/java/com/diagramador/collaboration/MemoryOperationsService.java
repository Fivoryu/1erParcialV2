package com.diagramador.collaboration;

import com.diagramador.api.ApiException;
import com.diagramador.api.DiagramMemoryResource;
import com.diagramador.api.MemoryOperationResources.MemoryWriteRequest;
import com.diagramador.api.MemoryOperationResources.SearchHit;
import com.diagramador.api.MemoryOperationResources.SearchRequest;
import com.diagramador.api.MemoryOperationResources.SearchResponse;
import com.diagramador.api.MemoryOperationResources.SearchSource;
import com.diagramador.api.ModelResource;
import com.diagramador.api.ModelResource.ClassifierResource;
import com.diagramador.api.ModelResource.OperationResource;
import com.diagramador.api.ModelResource.RelationshipResource;
import com.diagramador.api.ModelResource.ViewResource;
import com.diagramador.api.ProjectApiService;
import com.diagramador.api.ProjectResource;
import com.diagramador.domain.DiagramMemory;
import com.diagramador.domain.DiagramView;
import com.diagramador.domain.MemoryKind;
import com.diagramador.domain.MemoryStatus;
import com.diagramador.domain.ProjectMember;
import com.diagramador.domain.UserAccount;
import com.diagramador.identity.CurrentUser;
import com.diagramador.identity.CurrentUserContext;
import com.diagramador.identity.ProjectAuthorizationService;
import com.diagramador.persistence.DiagramMemoryRepository;
import com.diagramador.persistence.ProjectMemberRepository;
import com.diagramador.persistence.UserAccountRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Server-authorized, bounded memory operations for the later web-chat dispatcher. */
@Service
public class MemoryOperationsService {

    private static final int MAX_CONTENT_LENGTH = 2000;
    private static final int MAX_QUERY_LENGTH = 200;
    private static final int MAX_SEARCH_RESULTS = 25;
    private static final int DEFAULT_SEARCH_RESULTS = 10;
    private static final int MAX_MEMORY_CANDIDATES_PER_TERM = 50;
    private static final int MAX_QUERY_TERMS = 8;
    private static final int MAX_HIT_CONTENT_LENGTH = 1000;
    private static final Pattern QUERY_WORD = Pattern.compile("[\\p{L}\\p{N}]{2,}");

    private final ChatService chatService;
    private final DiagramMemoryRepository memoryRepository;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final ProjectApiService projectApiService;
    private final CurrentUserContext currentUserContext;
    private final UserAccountRepository userAccountRepository;
    private final ProjectMemberRepository projectMemberRepository;

    public MemoryOperationsService(ChatService chatService, DiagramMemoryRepository memoryRepository,
            ProjectAuthorizationService projectAuthorizationService, ProjectApiService projectApiService,
            CurrentUserContext currentUserContext, UserAccountRepository userAccountRepository,
            ProjectMemberRepository projectMemberRepository) {
        this.chatService = chatService;
        this.memoryRepository = memoryRepository;
        this.projectAuthorizationService = projectAuthorizationService;
        this.projectApiService = projectApiService;
        this.currentUserContext = currentUserContext;
        this.userAccountRepository = userAccountRepository;
        this.projectMemberRepository = projectMemberRepository;
    }

    @Transactional(readOnly = true)
    public DiagramMemoryResource read(UUID projectId, UUID viewId, UUID memoryId) {
        DiagramView view = chatService.requireAccessibleView(projectId, viewId);
        DiagramMemory memory = memoryRepository.findById(memoryId)
                .filter(candidate -> candidate.getView().getId().equals(view.getId()))
                .orElseThrow(() -> notFound("The memory was not found for this diagram."));
        return DiagramMemoryService.memoryResource(memory);
    }

    @Transactional
    public DiagramMemoryResource save(UUID projectId, UUID viewId, MemoryWriteRequest request) {
        DiagramView view = chatService.requireAccessibleView(projectId, viewId);
        projectAuthorizationService.requireEditor(projectId);
        return saveValidated(view, validateWrite(request));
    }

    /** Shared chat may persist source-derived memory only while every active collaborator retains access. */
    @Transactional
    public DiagramMemoryResource saveForSharedChat(UUID projectId, UUID viewId, MemoryWriteRequest request,
            Set<UUID> sourceProjectIds) {
        DiagramView view = chatService.requireAccessibleView(projectId, viewId);
        projectAuthorizationService.requireEditor(projectId);
        requireSharedSourcesRemainAccessible(projectId, viewId, sourceProjectIds);
        return saveValidated(view, validateWrite(request));
    }

    @Transactional
    public DiagramMemoryResource revise(UUID projectId, UUID viewId, UUID memoryId, MemoryWriteRequest request) {
        DiagramView view = chatService.requireAccessibleView(projectId, viewId);
        projectAuthorizationService.requireEditor(projectId);
        return reviseValidated(view, memoryId, validateWrite(request));
    }

    /** Shared-chat revision rechecks the source audience before changing replacement history. */
    @Transactional
    public DiagramMemoryResource reviseForSharedChat(UUID projectId, UUID viewId, UUID memoryId,
            MemoryWriteRequest request, Set<UUID> sourceProjectIds) {
        DiagramView view = chatService.requireAccessibleView(projectId, viewId);
        projectAuthorizationService.requireEditor(projectId);
        requireSharedSourcesRemainAccessible(projectId, viewId, sourceProjectIds);
        return reviseValidated(view, memoryId, validateWrite(request));
    }

    private DiagramMemoryResource saveValidated(DiagramView view, ValidatedMemory memory) {
        DiagramMemory created = DiagramMemory.create(view, memory.kind(), memory.content(), null, null, currentUser());
        return DiagramMemoryService.memoryResource(memoryRepository.saveAndFlush(created));
    }

    private DiagramMemoryResource reviseValidated(DiagramView view, UUID memoryId, ValidatedMemory replacement) {
        DiagramMemory predecessor = memoryRepository.findByIdForUpdate(memoryId)
                .orElseThrow(() -> notFound("The memory was not found for this diagram."));
        if (!predecessor.getView().getId().equals(view.getId())) {
            throw notFound("The memory was not found for this diagram.");
        }
        if (predecessor.getStatus() != MemoryStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "MEMORY_NOT_ACTIVE",
                    "Only an active memory can be revised.", List.of(), null);
        }

        DiagramMemory created = DiagramMemory.create(view, replacement.kind(), replacement.content(),
                null, null, currentUser());
        memoryRepository.saveAndFlush(created);
        predecessor.setStatus(MemoryStatus.REPLACED);
        predecessor.setReplacedBy(created);
        memoryRepository.flush();
        return DiagramMemoryService.memoryResource(created);
    }

    @Transactional(readOnly = true)
    public SearchResponse search(UUID projectId, UUID viewId, SearchRequest request) {
        ValidatedSearch search = validateSearch(request);
        chatService.requireAccessibleView(projectId, viewId);
        projectAuthorizationService.requireMember(projectId);

        boolean includeAccessibleProjects = Boolean.TRUE.equals(request.includeAccessibleProjects());
        List<ProjectResource> accessibleProjects = projectApiService.listProjects();
        ProjectResource currentProject = accessibleProjects.stream()
                .filter(project -> project.id().equals(projectId))
                .findFirst()
                .orElseThrow(() -> notFound("The project was not found."));
        List<ProjectResource> scope = includeAccessibleProjects
                ? accessibleProjects
                : List.of(currentProject);

        List<SearchHit> results = new ArrayList<>();
        for (ProjectResource project : scope) {
            // getModel rechecks active membership and supplies persisted project/model/view facts.
            ModelResource model = projectApiService.getModel(project.id());
            List<ViewResource> views = includeAccessibleProjects
                    ? model.views()
                    : model.views().stream().filter(view -> viewId.equals(view.id())).toList();
            SearchSource modelSource = new SearchSource(project.id(), project.name(), null, "UML model");

            addMemoryHits(results, project, views, search);
            if (results.size() < search.limit()) {
                addClassifierHits(results, modelSource, model.classifiers(), search);
            }
            if (results.size() < search.limit()) {
                addRelationshipHits(results, modelSource, model.relationships(), search);
            }
            if (results.size() < search.limit()) {
                addDiagramHits(results, project, views, search);
            }
            if (results.size() < search.limit()) {
                addProjectHit(results, project, search);
            }
            if (results.size() >= search.limit()) {
                break;
            }
        }
        return new SearchResponse(search.query(), results);
    }

    /** Shared-chat search narrows external candidates before loading any model or memory content. */
    @Transactional(readOnly = true)
    public SearchResponse searchForSharedChat(UUID projectId, UUID viewId, SearchRequest request) {
        ValidatedSearch search = validateSearch(request);
        chatService.requireAccessibleView(projectId, viewId);
        projectAuthorizationService.requireMember(projectId);
        if (currentUserContext.isOperator() || currentUserContext.optional().isEmpty()) {
            throw forbiddenSharedContext();
        }

        boolean includeExternal = Boolean.TRUE.equals(request.includeAccessibleProjects());
        List<ProjectResource> scope;
        if (!includeExternal) {
            scope = List.of(projectApiService.getProject(projectId));
        } else {
            Set<UUID> commonProjects = commonAccessibleProjects(projectId);
            if (!commonProjects.contains(projectId)) {
                throw forbiddenSharedContext();
            }
            // Membership intersection is computed before any external project/model is loaded.
            List<UUID> externalProjectIds = commonProjects.stream()
                    .filter(commonProjectId -> !projectId.equals(commonProjectId))
                    .sorted()
                    .toList();
            List<ProjectResource> sharedScope = new ArrayList<>(externalProjectIds.stream()
                    .map(projectApiService::getProject).toList());
            sharedScope.add(projectApiService.getProject(projectId));
            scope = List.copyOf(sharedScope);
        }

        List<SearchHit> results = new ArrayList<>();
        for (ProjectResource project : scope) {
            ModelResource model = projectApiService.getModel(project.id());
            List<ViewResource> views = includeExternal
                    ? model.views()
                    : model.views().stream().filter(view -> viewId.equals(view.id())).toList();
            SearchSource modelSource = new SearchSource(project.id(), project.name(), null, "UML model");
            addMemoryHits(results, project, views, search);
            if (results.size() < search.limit()) {
                addClassifierHits(results, modelSource, model.classifiers(), search);
            }
            if (results.size() < search.limit()) {
                addRelationshipHits(results, modelSource, model.relationships(), search);
            }
            if (results.size() < search.limit()) {
                addDiagramHits(results, project, views, search);
            }
            if (results.size() < search.limit()) {
                addProjectHit(results, project, search);
            }
            if (results.size() >= search.limit()) {
                break;
            }
        }
        return new SearchResponse(search.query(), results);
    }

    @Transactional(readOnly = true)
    public boolean sharedSourcesRemainAccessible(UUID projectId, UUID viewId, Set<UUID> sourceProjectIds) {
        chatService.requireAccessibleView(projectId, viewId);
        projectAuthorizationService.requireMember(projectId);
        Set<UUID> commonProjects = commonAccessibleProjects(projectId);
        return commonProjects.contains(projectId) && commonProjects.containsAll(sourceProjectIds);
    }

    private void requireSharedSourcesRemainAccessible(UUID projectId, UUID viewId, Set<UUID> sourceProjectIds) {
        if (sourceProjectIds == null || !sharedSourcesRemainAccessible(projectId, viewId, sourceProjectIds)) {
            throw forbiddenSharedContext();
        }
    }

    private Set<UUID> commonAccessibleProjects(UUID projectId) {
        UUID requesterId = currentUserContext.require().userId();
        List<ProjectMember> audience = projectMemberRepository.findByProjectId(projectId).stream()
                .filter(member -> "ACTIVE".equals(member.getStatus()))
                .toList();
        if (audience.isEmpty() || audience.stream().noneMatch(member -> requesterId.equals(member.getId().getUserId()))) {
            return Set.of();
        }

        Set<UUID> common = null;
        for (ProjectMember collaborator : audience) {
            UUID collaboratorId = collaborator.getId().getUserId();
            Set<UUID> activeProjects = projectMemberRepository.findByUserId(collaboratorId).stream()
                    .filter(member -> "ACTIVE".equals(member.getStatus()))
                    .map(member -> member.getId().getProjectId())
                    .collect(java.util.stream.Collectors.toSet());
            if (common == null) {
                common = activeProjects;
            } else {
                common.retainAll(activeProjects);
            }
            if (common.isEmpty()) {
                return Set.of();
            }
        }
        return common == null ? Set.of() : Set.copyOf(common);
    }

    private static ApiException forbiddenSharedContext() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "The shared context is unavailable.", List.of(), null);
    }

    private void addMemoryHits(List<SearchHit> results, ProjectResource project, List<ViewResource> views,
            ValidatedSearch search) {
        for (ViewResource view : views) {
            Set<UUID> added = new HashSet<>();
            for (String term : search.terms()) {
                List<DiagramMemory> candidates = memoryRepository
                        .findByView_IdAndContentContainingIgnoreCaseOrderByCreatedAtDescIdDesc(view.id(), term,
                                PageRequest.of(0, MAX_MEMORY_CANDIDATES_PER_TERM));
                for (DiagramMemory memory : candidates) {
                    if (added.add(memory.getId())) {
                        results.add(new SearchHit("MEMORY", truncate(memory.getContent()),
                                source(project, view), memory.getId(), memory.getId(),
                                memory.getKind().name(), memory.getStatus().name()));
                        if (results.size() >= search.limit()) {
                            return;
                        }
                    }
                }
            }
        }
    }

    private void addClassifierHits(List<SearchHit> results, SearchSource source,
            List<ClassifierResource> classifiers, ValidatedSearch search) {
        for (ClassifierResource classifier : classifiers) {
            String content = classifierSummary(classifier);
            if (matches(content, search.terms())) {
                results.add(new SearchHit("UML_CLASSIFIER", truncate(content), source,
                        classifier.id(), null, classifier.kind().name(), null));
                if (results.size() >= search.limit()) {
                    return;
                }
            }
        }
    }

    private void addRelationshipHits(List<SearchHit> results, SearchSource source,
            List<RelationshipResource> relationships, ValidatedSearch search) {
        for (RelationshipResource relationship : relationships) {
            String content = relationshipSummary(relationship);
            if (matches(content, search.terms())) {
                results.add(new SearchHit("UML_RELATIONSHIP", truncate(content), source,
                        relationship.id(), null, relationship.kind(), null));
                if (results.size() >= search.limit()) {
                    return;
                }
            }
        }
    }

    private void addDiagramHits(List<SearchHit> results, ProjectResource project, List<ViewResource> views,
            ValidatedSearch search) {
        for (ViewResource view : views) {
            String content = "Diagram " + view.name() + (view.isDefault() ? " (default)" : "");
            if (matches(content, search.terms())) {
                results.add(new SearchHit("DIAGRAM", truncate(content), source(project, view),
                        view.id(), null, null, null));
                if (results.size() >= search.limit()) {
                    return;
                }
            }
        }
    }

    private void addProjectHit(List<SearchHit> results, ProjectResource project, ValidatedSearch search) {
        String content = project.description() == null || project.description().isBlank()
                ? "Project " + project.name()
                : "Project " + project.name() + ". " + project.description();
        if (matches(project.name() + " " + nullToEmpty(project.description()), search.terms())) {
            results.add(new SearchHit("PROJECT", truncate(content),
                    new SearchSource(project.id(), project.name(), null, null), project.id(), null, null, null));
        }
    }

    private static SearchSource source(ProjectResource project, ViewResource view) {
        return new SearchSource(project.id(), project.name(), view.id(), view.name());
    }

    private static String classifierSummary(ClassifierResource classifier) {
        StringBuilder summary = new StringBuilder("Classifier ").append(classifier.name())
                .append(" (").append(classifier.kind()).append(')');
        classifier.attributes().stream().limit(12).forEach(attribute -> summary.append("; attribute ")
                .append(attribute.name()).append(": ").append(nullToEmpty(attribute.type())));
        for (OperationResource operation : classifier.operations().stream().limit(12).toList()) {
            summary.append("; operation ").append(operation.name()).append(" returns ")
                    .append(nullToEmpty(operation.returnType()));
        }
        classifier.constraints().stream().limit(8).forEach(constraint -> summary.append("; constraint ")
                .append(constraint.expression()));
        return summary.toString();
    }

    private static String relationshipSummary(RelationshipResource relationship) {
        StringBuilder summary = new StringBuilder("Relationship ").append(relationship.kind())
                .append(' ').append(nullToEmpty(relationship.name()));
        relationship.ends().forEach(end -> summary.append("; role ").append(nullToEmpty(end.role()))
                .append(" classifier ").append(end.classifierId())
                .append(" multiplicity ").append(end.multiplicity()));
        relationship.constraints().stream().limit(8).forEach(constraint -> summary.append("; constraint ")
                .append(constraint.expression()));
        return summary.toString();
    }

    private static ValidatedMemory validateWrite(MemoryWriteRequest request) {
        if (request == null || request.kind() == null || request.content() == null) {
            throw invalid("kind and content are required.");
        }
        MemoryKind kind;
        try {
            kind = MemoryKind.valueOf(request.kind().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw invalid("kind must be DECISION or HYPOTHESIS.");
        }
        String content = request.content();
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw invalid("content must be at most 2000 characters.");
        }
        content = content.strip();
        if (content.isEmpty()) {
            throw invalid("content must not be blank.");
        }
        return new ValidatedMemory(kind, content);
    }

    private static ValidatedSearch validateSearch(SearchRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw invalid("query is required.");
        }
        if (request.query().length() > MAX_QUERY_LENGTH) {
            throw invalid("query must be at most 200 characters.");
        }
        int limit = request.limit() == null ? DEFAULT_SEARCH_RESULTS : request.limit();
        if (limit < 1) {
            throw invalid("limit must be at least 1.");
        }
        limit = Math.min(limit, MAX_SEARCH_RESULTS);
        List<String> terms = queryTerms(request.query());
        return new ValidatedSearch(request.query().strip(), terms, limit);
    }

    private static List<String> queryTerms(String query) {
        Matcher matcher = QUERY_WORD.matcher(query.toLowerCase(Locale.ROOT));
        List<String> terms = new ArrayList<>();
        while (matcher.find() && terms.size() < MAX_QUERY_TERMS) {
            terms.add(matcher.group());
        }
        if (terms.isEmpty()) {
            terms.add(query.toLowerCase(Locale.ROOT).strip());
        }
        return List.copyOf(terms);
    }

    private static boolean matches(String content, List<String> terms) {
        if (content == null) {
            return false;
        }
        String normalized = content.toLowerCase(Locale.ROOT);
        return terms.stream().anyMatch(normalized::contains);
    }

    private UserAccount currentUser() {
        return currentUserContext.optional()
                .map(CurrentUser::userId)
                .flatMap(userAccountRepository::findById)
                .orElse(null);
    }

    private static String truncate(String content) {
        if (content == null || content.length() <= MAX_HIT_CONTENT_LENGTH) {
            return content;
        }
        return content.substring(0, MAX_HIT_CONTENT_LENGTH);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAYLOAD", message, List.of(), null);
    }

    private static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message, List.of(), null);
    }

    private record ValidatedMemory(MemoryKind kind, String content) {
    }

    private record ValidatedSearch(String query, List<String> terms, int limit) {
    }
}
