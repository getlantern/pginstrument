package org.lantern.pginstrument;

import java.util.ArrayList;
import java.util.Collection;

public class TestInstrumenter {
    public static void main(String[] args) {
        Collection<String> strings = new ArrayList<String>();
        strings.add(String.format("Hello %1$s", "World"));
        System.out.println("Size: " + strings.size());
    }
}
