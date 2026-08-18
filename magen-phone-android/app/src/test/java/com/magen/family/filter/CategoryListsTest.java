package com.magen.family.filter;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * בדיקות לחילוץ ליבת הדומיין. זו הלוגיקה שקובעת אם וריאנט אזורי
 * (bet365.co.uk) או סאב-דומיין (m.facebook.com) ייתפסו בקטגוריה.
 * קודם ההתאמה הייתה על "co.uk" והווריאנטים חמקו.
 */
public class CategoryListsTest {

    @Test
    public void plainDomain() {
        assertEquals("facebook", CategoryLists.core("facebook.com"));
    }

    @Test
    public void stripsSubdomain() {
        assertEquals("facebook", CategoryLists.core("m.facebook.com"));
        assertEquals("facebook", CategoryLists.core("www.facebook.com"));
    }

    @Test
    public void handlesTwoPartTld() {
        assertEquals("bet365", CategoryLists.core("bet365.co.uk"));
        assertEquals("example", CategoryLists.core("news.example.co.il"));
        assertEquals("amazon", CategoryLists.core("amazon.com.au"));
    }

    @Test
    public void handlesTrailingDotAndCase() {
        assertEquals("tinder", CategoryLists.core("Tinder.COM."));
    }

    @Test
    public void singleLabelIsSafe() {
        assertEquals("localhost", CategoryLists.core("localhost"));
    }
}
