package com.fehlenfusion.immichscreensaver;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Calendar;
import java.util.GregorianCalendar;

public class ChristmasCountdownTest {
    private static int days(int y, int m, int d, int hour) {
        return ChristmasView.daysUntilChristmas(new GregorianCalendar(y, m - 1, d, hour, 30));
    }

    @Test
    public void countsDownToTheNext25December() {
        assertEquals(33, days(2026, 11, 22, 12));
        assertEquals(1, days(2026, 12, 24, 23));
        assertEquals(0, days(2026, 12, 25, 0));
        assertEquals(0, days(2026, 12, 25, 23));
    }

    @Test
    public void rollsOverToNextYearAfterChristmas() {
        assertEquals(364, days(2026, 12, 26, 8));
        assertEquals(358, days(2027, 1, 1, 8));
    }

    @Test
    public void labels() {
        assertEquals("33 days until Christmas", ChristmasView.countdownLabel(33));
        assertEquals("1 day until Christmas", ChristmasView.countdownLabel(1));
        assertEquals("It's Christmas Day!", ChristmasView.countdownLabel(0));
    }
}
