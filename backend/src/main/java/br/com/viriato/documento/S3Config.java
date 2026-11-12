package br.com.viriato.documento;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@ConditionalOnProperty(name = "viriato.armazenamento.tipo", havingValue = "s3", matchIfMissing = true)
@Configuration
public class S3Config {

    @Bean(destroyMethod = "close")
    S3Client s3Client(@Value("${viriato.s3.regiao}") String regiao,
                      @Value("${viriato.s3.endpoint}") String endpoint,
                      @Value("${viriato.s3.access-key}") String accessKey,
                      @Value("${viriato.s3.secret-key}") String secretKey) {
        AwsCredentialsProvider creds = StringUtils.hasText(accessKey)
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
                : DefaultCredentialsProvider.create(); // IAM role em produção (ECS/EC2)
        var b = S3Client.builder().region(Region.of(regiao)).credentialsProvider(creds);
        if (StringUtils.hasText(endpoint)) { // MinIO / S3 compatível (desenvolvimento)
            b.endpointOverride(URI.create(endpoint))
             .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        return b.build();
    }
}
