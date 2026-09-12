package com.modose.app.flow.recovery

enum class ProductFailureDomain {
    Device,
    Permission,
    Ar,
    Capture,
    Authentication,
    Network,
    VlmContract,
    Correspondence,
    Storage,
    Unknown,
}

enum class ProductFailureCode(val domain: ProductFailureDomain) {
    DeviceUnsupported(ProductFailureDomain.Device),
    CameraUnavailable(ProductFailureDomain.Device),
    CameraPermissionDenied(ProductFailureDomain.Permission),
    CameraPermissionPermanentlyDenied(ProductFailureDomain.Permission),

    ArCoreInstallRequired(ProductFailureDomain.Ar),
    ArCoreUpdateRequired(ProductFailureDomain.Ar),
    ArCoreUnsupported(ProductFailureDomain.Ar),
    ArSessionCreationFailed(ProductFailureDomain.Ar),
    ArTrackingLost(ProductFailureDomain.Ar),
    HorizontalPlaneUnavailable(ProductFailureDomain.Ar),
    AnchorUnavailable(ProductFailureDomain.Ar),

    CpuImageUnavailable(ProductFailureDomain.Capture),
    ImageTooDark(ProductFailureDomain.Capture),
    ImageBlurred(ProductFailureDomain.Capture),
    ImageTooLarge(ProductFailureDomain.Capture),
    RequestTooLarge(ProductFailureDomain.Capture),
    TooManyObjects(ProductFailureDomain.Capture),

    IdTokenUnavailable(ProductFailureDomain.Authentication),
    AppCheckTokenUnavailable(ProductFailureDomain.Authentication),
    AuthenticationRejected(ProductFailureDomain.Authentication),
    AppCheckRejected(ProductFailureDomain.Authentication),

    NetworkUnavailable(ProductFailureDomain.Network),
    RequestTimedOut(ProductFailureDomain.Network),
    RateLimited(ProductFailureDomain.Network),
    ServerUnavailable(ProductFailureDomain.Network),
    NonRetryableHttpFailure(ProductFailureDomain.Network),
    ResponseTooLarge(ProductFailureDomain.Network),

    MalformedVlmJson(ProductFailureDomain.VlmContract),
    UnsupportedSchema(ProductFailureDomain.VlmContract),
    InvalidVlmStatus(ProductFailureDomain.VlmContract),
    InvalidModel(ProductFailureDomain.VlmContract),
    InvalidPromptVersion(ProductFailureDomain.VlmContract),
    UnknownVlmEnum(ProductFailureDomain.VlmContract),
    InvalidVlmPayload(ProductFailureDomain.VlmContract),

    AmbiguousCorrespondence(ProductFailureDomain.Correspondence),
    MissingObject(ProductFailureDomain.Correspondence),
    AssignmentConflict(ProductFailureDomain.Correspondence),
    TrackingIdentityLost(ProductFailureDomain.Correspondence),

    LocalReadFailed(ProductFailureDomain.Storage),
    LocalWriteFailed(ProductFailureDomain.Storage),
    LocalDeleteFailed(ProductFailureDomain.Storage),
    MetadataDeleteFailed(ProductFailureDomain.Storage),

    UnknownFailure(ProductFailureDomain.Unknown),
}

enum class ProductFailureStage {
    Startup,
    BaselineCapture,
    BaselineAnalysis,
    SceneComparison,
    ObjectGuidance,
    FinalVerification,
    SceneReset,
}

data class ProductFailure(
    val code: ProductFailureCode,
    val stage: ProductFailureStage,
    val automaticAttemptsUsed: Int = 0,
) {
    init {
        require(automaticAttemptsUsed >= 0)
    }
}
