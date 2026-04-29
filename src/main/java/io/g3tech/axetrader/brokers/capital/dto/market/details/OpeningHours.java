package io.g3tech.axetrader.brokers.capital.dto.market.details;

import java.util.List;

/** Market schedule */
public record OpeningHours(
        List<String> mon,
        List<String> tue,
        List<String> wed,
        List<String> thu,
        List<String> fri,
        List<String> sat,
        List<String> sun
) {
    public OpeningHours {
        mon = mon == null ? List.of() : List.copyOf(mon);
        tue = tue == null ? List.of() : List.copyOf(tue);
        wed = wed == null ? List.of() : List.copyOf(wed);
        thu = thu == null ? List.of() : List.copyOf(thu);
        fri = fri == null ? List.of() : List.copyOf(fri);
        sat = sat == null ? List.of() : List.copyOf(sat);
        sun = sun == null ? List.of() : List.copyOf(sun);
    }
}
