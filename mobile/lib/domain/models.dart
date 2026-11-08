class UserSession {
  const UserSession({
    required this.token,
    required this.expiresAt,
    required this.user,
  });
  final String token;
  final DateTime expiresAt;
  final AppUser user;
  bool get isExpired => !expiresAt.isAfter(DateTime.now().toUtc());
}

class AppUser {
  const AppUser({
    required this.id,
    required this.username,
    required this.displayName,
    required this.email,
  });
  final String id;
  final String username;
  final String displayName;
  final String? email;
}

class GatewayResult {
  const GatewayResult({required this.status, required this.body});
  final int status;
  final dynamic body;
  bool get successful => status >= 200 && status < 300;
}

class PlatformProject {
  const PlatformProject({required this.id, required this.name});
  final String id;
  final String name;
}

class MergeRequestSummary {
  const MergeRequestSummary({
    required this.id,
    required this.title,
    required this.state,
    required this.sourceBranch,
    required this.targetBranch,
  });
  final String id;
  final String title;
  final String state;
  final String sourceBranch;
  final String targetBranch;
}

class GeneratedBackend {
  const GeneratedBackend({
    required this.deploymentId,
    required this.name,
    required this.state,
    required this.runId,
    required this.contractHash,
  });
  final String deploymentId;
  final String name;
  final String state;
  final String runId;
  final String contractHash;
}

class BackendContract {
  const BackendContract({
    required this.deploymentId,
    required this.runId,
    required this.contractHash,
    required this.openApi,
  });
  final String deploymentId;
  final String runId;
  final String contractHash;
  final Map<String, dynamic> openApi;
}

class MnnStatus {
  const MnnStatus({
    required this.modelDirectory,
    required this.isLoaded,
    required this.message,
  });
  final String? modelDirectory;
  final bool isLoaded;
  final String message;
}
