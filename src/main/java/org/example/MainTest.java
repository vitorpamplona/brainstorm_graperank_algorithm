package org.example;

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
import org.example.grape.GrapeRankAlgorithm;
import org.example.grape.GrapeRankResult;
import org.neo4j.driver.*;

public class MainTest {

    public static void main(String[] args) {
        String observer = "f18571e72c93044670953ee53f03e6a500a6d97689b6ffcfe1cdcccf9da08ff4";

        GrapeRankAlgorithm helper = new GrapeRankAlgorithm();

        GrapeRankResult result = helper.graperankAllSteps(observer);

        System.out.println("Success: " + result.isSuccess());
        System.out.println("Duration (seconds): " + result.getDurationSeconds());

    }
}