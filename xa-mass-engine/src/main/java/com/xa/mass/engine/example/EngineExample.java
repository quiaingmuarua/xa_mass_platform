package com.xa.mass.engine.example;

import com.xa.mass.base.jsondsl.model.JsonDslDefinition;
import com.xa.mass.base.jsondsl.processor.GenerateProcessor;
import com.xa.mass.base.jsondsl.processor.ProcessingContext;
import com.xa.mass.base.jsondsl.processor.ProcessorRegistry;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.storage.DeviceStorage;
import com.xa.mass.engine.storage.InMemoryDeviceStorage;
import com.xa.mass.engine.storage.InMemoryTaskStorage;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EngineExample {


    private static final Logger log = LoggerFactory.getLogger(EngineExample.class);

    public static void main(String[] args) {
         TaskManager taskManager = new TaskManager(new SimpleTaskScheduler(),new InMemoryTaskStorage());
         DeviceManager deviceManager = new DeviceManager(new InMemoryDeviceStorage());
         log.info("taskManager:"+taskManager );
         log.info("deviceManager:"+deviceManager );
        //鐩戝惉浠诲姟鏄惁闇€瑕佸垎閰嶈澶?



        //鐢熸垚璁惧
        List<Device> devices= genMockDevice();
        devices.forEach(deviceManager::addDevice);
        //涓妕oken
        List<Token> tokens=genMockToken();
        tokens.forEach(token -> {deviceManager.addToken(token.getDeviceId(),token);});
        //鐢熸垚浠诲姟
        TaskCreateRequestDto task=new TaskCreateRequestDto();
        taskManager.createTask(task);

        //

        //妯℃嫙瀹℃牳





    }



    public static Task genMockTask(){
        return null;
    }

    public static List<Token> genMockToken(){
        return null;
    }


    public static  List<Device> genMockDevice(){

        JsonDslDefinition definition = new JsonDslDefinition("device_generator", JsonDslDefinition.DslType.GENERATE);
        definition.setDescription("鐢熸垚 300 涓祴璇曡澶?);
        // 3. 璁剧疆瀛楁 DSL
        Map<String, Object> fieldDsl = new HashMap<>();
        fieldDsl.put("deviceId", Map.of("$JOIN", Arrays.asList("", "&.index")));
        fieldDsl.put("deviceGroupId", Map.of("$RANGE", Arrays.asList(16, 65)));
        fieldDsl.put("status", Map.of("$CHOICE", Arrays.asList("OFFLINE", "ONLINE")));
        definition.setFieldDsl(fieldDsl);
        GenerateProcessor processor = ProcessorRegistry.getGenerateProcessor();
        return processor.generate(definition, new ProcessingContext("test-context"), Device.class);
    }
}
