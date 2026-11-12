package br.com.viriato.documento;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

@ConditionalOnProperty(name = "viriato.armazenamento.tipo", havingValue = "s3", matchIfMissing = true)
@Component
public class S3Armazenamento implements ArmazenamentoArquivos {
    private static final Logger log = LoggerFactory.getLogger(S3Armazenamento.class);

    private final S3Client s3;
    private final String bucket;
    private final boolean criarBucket;

    public S3Armazenamento(S3Client s3, @Value("${viriato.s3.bucket}") String bucket,
                           @Value("${viriato.s3.endpoint}") String endpoint) {
        this.s3 = s3;
        this.bucket = bucket;
        this.criarBucket = StringUtils.hasText(endpoint); // só em dev (MinIO); na AWS o bucket é provisionado
    }

    @PostConstruct
    void garantirBucketEmDev() {
        if (!criarBucket) return;
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (Exception e) {
            log.warn("Não foi possível verificar o bucket '{}': {}", bucket, e.getMessage());
        }
    }

    @Override
    public void salvar(String chave, InputStream conteudo, long tamanho, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket).key(chave).contentType(contentType)
                        .serverSideEncryption(ServerSideEncryption.AES256)
                        .build(),
                RequestBody.fromInputStream(conteudo, tamanho));
    }

    @Override
    public InputStream abrir(String chave) {
        return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(chave).build());
    }

    @Override
    public void remover(String chave) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(chave).build());
    }
}
