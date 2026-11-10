package br.com.viriato.comum;

import java.time.LocalDate;
import java.time.ZoneId;

public final class Datas {
    public static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
    private Datas() {}
    public static LocalDate hoje() { return LocalDate.now(ZONA); }
}
