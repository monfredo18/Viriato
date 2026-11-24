package br.com.viriato;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.viriato.crm.Documentos;
import org.junit.jupiter.api.Test;

class DocumentosFiscaisTest {
    @Test
    void cnpj() {
        assertThat(Documentos.cnpjValido("11.222.333/0001-81")).isTrue();
        assertThat(Documentos.cnpjValido("11222333000181")).isTrue();
        assertThat(Documentos.cnpjValido("11222333000182")).isFalse();     // dígito errado
        assertThat(Documentos.cnpjValido("11111111111111")).isFalse();     // todos iguais
        assertThat(Documentos.cnpjValido("123")).isFalse();
        assertThat(Documentos.cnpjValido(null)).isFalse();
        assertThat(Documentos.formatarCnpj("11222333000181")).isEqualTo("11.222.333/0001-81");
    }

    @Test
    void cpf() {
        assertThat(Documentos.cpfValido("529.982.247-25")).isTrue();
        assertThat(Documentos.cpfValido("52998224726")).isFalse();
        assertThat(Documentos.cpfValido("00000000000")).isFalse();
        assertThat(Documentos.mascararCpf("52998224725")).isEqualTo("***.982.247-**");
    }
}
