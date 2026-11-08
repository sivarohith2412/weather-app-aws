package dev.weather.infra;

import java.util.Map;
import software.constructs.Construct;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.services.s3.*;
import software.amazon.awscdk.services.cloudfront.*;
import software.amazon.awscdk.services.cloudfront.origins.S3BucketOrigin;

/** Private files served through one HTTPS CloudFront distribution. */
public final class DashboardHosting extends Construct {
    public static final String BUCKET_NAME = "weather-dashboard-dev-471084227270-us-east-2";
    private final Bucket bucket;
    private final Distribution distribution;
    public DashboardHosting(Construct scope, String id) {
        super(scope, id);
        bucket = Bucket.Builder.create(this, "Files").bucketName(BUCKET_NAME)
                .encryption(BucketEncryption.S3_MANAGED).blockPublicAccess(BlockPublicAccess.BLOCK_ALL)
                .objectOwnership(ObjectOwnership.BUCKET_OWNER_ENFORCED).enforceSsl(true)
                .removalPolicy(RemovalPolicy.RETAIN_ON_UPDATE_OR_DELETE).build();
        var origin = S3BucketOrigin.withOriginAccessControl(bucket);
        var assets = BehaviorOptions.builder().origin(origin).compress(true)
                .allowedMethods(AllowedMethods.ALLOW_GET_HEAD_OPTIONS)
                .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                .cachePolicy(CachePolicy.CACHING_OPTIMIZED)
                .responseHeadersPolicy(ResponseHeadersPolicy.SECURITY_HEADERS).build();
        distribution = Distribution.Builder.create(this, "Cdn")
                .comment("weather-dashboard-dev").defaultRootObject("index.html")
                .priceClass(PriceClass.PRICE_CLASS_100).enableIpv6(true)
                .httpVersion(HttpVersion.HTTP2_AND_3).minimumProtocolVersion(SecurityPolicyProtocol.TLS_V1_2_2021)
                .defaultBehavior(BehaviorOptions.builder().origin(origin).compress(true)
                        .allowedMethods(AllowedMethods.ALLOW_GET_HEAD_OPTIONS)
                        .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                        .cachePolicy(CachePolicy.CACHING_DISABLED)
                        .responseHeadersPolicy(ResponseHeadersPolicy.SECURITY_HEADERS).build())
                .additionalBehaviors(Map.of("*.js", assets, "*.css", assets)).build();
    }
    public String url() { return "https://" + distribution.getDistributionDomainName(); }
    public String bucketName() { return bucket.getBucketName(); }
    public String distributionId() { return distribution.getDistributionId(); }
}
