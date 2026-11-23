package br.com.viriato.comum;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class Datas {
    public static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
    public static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    public static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private Datas() {}
    public static LocalDate hoje() { return LocalDate.now(ZONA); }
}
