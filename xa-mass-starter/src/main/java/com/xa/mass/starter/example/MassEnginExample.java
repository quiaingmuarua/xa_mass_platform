package com.xa.mass.starter.example;

import com.xa.mass.starter.MassEngine;
import com.xa.mass.starter.config.EngineConfig;

public class MassEnginExample {


    public static void main(String[] args) {
        EngineConfig engineConfig = new EngineConfig();
        MassEngine massEngine = new MassEngine(engineConfig);


        massEngine.start();
    }
}
