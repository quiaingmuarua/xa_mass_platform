package com.xa.mass.mock.command.util;

import java.util.*;

public class EuroNameGenerator {

    static String[] start = {
            "Al","Ben","Car","Dan","El","Jo","Mar","Ol","Sam","Wil",
            "Leo","Max","No","Ra","Lu","Vi","Da","Ro","Te","Ke"
    };

    static String[] middle = {
            "a","e","i","o","u","er","an","on","el","ar",
            "in","or","us","ia","eo","ra","lo","mi","ta","ne"
    };

    static String[] end = {
            "son","ton","man","ley","ford","den","lin","ers","vin","bert",
            "sen","berg","wood","field","stone","well","mont","rick","dell","port"
    };

    static String[] lastNames = {
            "Smith","Johnson","Brown","Taylor","Anderson",
            "Thomas","Jackson","White","Harris","Martin",
            "Clark","Lewis","Walker","Hall","Allen",
            "Young","King","Scott","Green","Baker"
    };

    public static String makeFirstName() {
        StringBuilder name = new StringBuilder();

        name.append(start[(int)(Math.random() * start.length)]);

        int middleCount = 1 + (int)(Math.random() * 2); // 1~2个

        for (int i = 0; i < middleCount; i++) {
            name.append(middle[(int)(Math.random() * middle.length)]);
        }

        name.append(end[(int)(Math.random() * end.length)]);

        return name.toString();
    }


    public static String makeLastName() {
        return lastNames[(int)(Math.random() * lastNames.length)];
    }


}
