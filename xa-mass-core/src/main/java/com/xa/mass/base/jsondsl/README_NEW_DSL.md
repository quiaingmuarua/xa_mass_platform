# 鏂扮増 JSON-DSL 鏍囧噯鏂囨。

## 鐩綍

1. [璁捐鐩爣](#璁捐鐩爣)
2. [鏍囧噯缁撴瀯浣揮(#鏍囧噯缁撴瀯浣?
3. [鏍稿績瀛楁璇存槑](#鏍稿績瀛楁璇存槑)
4. [绫诲瀷涓庣敤娉昡(#绫诲瀷涓庣敤娉?
5. [琛ㄨ揪寮忎笌寮曟搸](#琛ㄨ揪寮忎笌寮曟搸)
6. [澶氭潵婧愬悎骞朵笌浼樺厛绾(#澶氭潵婧愬悎骞朵笌浼樺厛绾?
7. [鍐茬獊妫€娴嬩笌璋冭瘯](#鍐茬獊妫€娴嬩笌璋冭瘯)
8. [鍙墿灞曠偣](#鍙墿灞曠偣)
9. [涓庢棫鐗堝尯鍒玗(#涓庢棫鐗堝尯鍒?
10. [甯歌鐢ㄦ硶绀轰緥](#甯歌鐢ㄦ硶绀轰緥)
11. [璋冭瘯涓庡畨鍏ㄥ缓璁甝(#璋冭瘯涓庡畨鍏ㄥ缓璁?

---

## 1. 璁捐鐩爣

- **缁撴瀯鍖?*锛氱粺涓€ DSL 缁撴瀯锛屼究浜庤В鏋愩€佹墿灞曞拰鎺掓煡銆?
- **鍙墿灞?*锛氭敮鎸佸绉嶇被鍨嬶紙鐢熸垚銆佽繃婊ゃ€佽浆鎹€佹牎楠岀瓑锛夛紝鍙彃鎷旇〃杈惧紡/瑙勫垯寮曟搸銆?
- **浼樺厛绾у悎骞?*锛氬鏉ユ簮瑙勫垯鍚堝苟鏃堕珮浼樺厛绾ц鐩栦綆浼樺厛绾с€?
- **鍏煎鎬?*锛氬吋瀹规棫鐗?DSL锛屾敮鎸佽嚜鍔ㄨ浆鎹€?
- **璋冭瘯鍙嬪ソ**锛氬敮涓€ ID銆佸厓鏁版嵁銆佽皟璇曟ā寮忋€佸啿绐佹娴嬨€?

---

## 2. 鏍囧噯缁撴瀯浣?

```json
{
  "unique_id": "string",
  // 鍞竴鏍囪瘑
  "type": "generate|filter|transform|validate",
  // DSL 绫诲瀷
  "priority": 10,
  // 浼樺厛绾э紝鏁板€艰秺澶т紭鍏堢骇瓒婇珮
  "desc": "鎻忚堪淇℃伅",
  "version": "1.0",
  "author": "浣滆€?,
  "tags": [
    "tag1",
    "tag2"
  ],
  "enabled": true,
  // 鏄惁鍚敤
  "context": {
    ...
  },
  // 涓婁笅鏂囬厤缃紝瑙佷笅
  "fieldDsl": {
    ...
  },
  // 瀛楁瑙勫垯
  "combine_dsl": {
    ...
  },
  // 缁勫悎瑙勫垯锛堝瀛楁/澶嶆潅琛ㄨ揪寮忥級
  "extensions": {
    ...
  },
  // 鎵╁睍淇℃伅
  "cacheable": false,
  // 鏄惁鍙紦瀛?
  "cache_expire_seconds": 300
  // 缂撳瓨杩囨湡鏃堕棿
}
```

### context 瀛楁缁撴瀯

```json
{
  "MODEL": "Worker",           // 妯″瀷绫诲悕鎴栨敞鍐屽埆鍚?
  "COUNT": 3,                   // 鐢熸垚鏁伴噺锛岄粯璁?1
  "TYPE": "LIST",              // 闆嗗悎绫诲瀷锛歀IST/SET/MAP
  "scope_name": "Worker",      // 浣滅敤鍩熷悕绉?
  "parent_scope": "Parent",    // 鐖朵綔鐢ㄥ煙寮曠敤
  "parameters": { ... },        // 棰濆鍙傛暟
  "debug": false,               // 璋冭瘯妯″紡
  "strict": true                // 涓ユ牸妯″紡
}
```

---

## 3. 鏍稿績瀛楁璇存槑

- **unique_id**锛氭瘡鏉?DSL 鐨勫敮涓€鏍囪瘑锛屼究浜庤拷韪拰璋冭瘯銆?
- **type**锛欴SL 绫诲瀷锛屾敮鎸?generate锛堢敓鎴愶級銆乫ilter锛堣繃婊わ級銆乼ransform锛堣浆鎹級銆乿alidate锛堟牎楠岋級銆?
- **priority**锛氬悎骞舵椂鐨勪紭鍏堢骇锛屾暟鍊艰秺澶т紭鍏堢骇瓒婇珮銆?
- **context**锛氫笂涓嬫枃閰嶇疆锛屽喅瀹氱敓鎴?杩囨护鐨勬ā鍨嬨€佹暟閲忋€佷綔鐢ㄥ煙绛夈€?
- **fieldDsl**锛氬瓧娈电骇瑙勫垯锛屾敮鎸佸唴缃嚱鏁般€佽〃杈惧紡銆佸祵濂楀璞°€侀泦鍚堢瓑銆?
- **combine_dsl**锛氬瀛楁鑱斿悎鍒ゆ柇銆佸鏉備笟鍔￠€昏緫銆?
- **extensions**锛氳嚜瀹氫箟鎵╁睍淇℃伅锛屼究浜庝笟鍔℃墿灞曘€?
- **enabled/cacheable**锛氬彲鎺у紑鍏充笌缂撳瓨绛栫暐銆?

---

## 4. 绫诲瀷涓庣敤娉?

- **generate**锛氭壒閲忕敓鎴愬璞★紝鏀寔閫掑綊宓屽銆佸唴缃嚱鏁般€佽〃杈惧紡銆?
- **filter**锛氬璞¤繃婊わ紝鏀寔瀛楁鏉′欢銆佽〃杈惧紡銆佺粍鍚堣鍒欍€?
- **transform**锛氬璞¤浆鎹紝鏀寔瀛楁鏄犲皠銆佽〃杈惧紡銆佹壒閲忓鐞嗐€?
- **validate**锛氬璞℃牎楠岋紝鏀寔蹇呭～銆佹鍒欍€佹灇涓俱€佸鏉傛牎楠岃〃杈惧紡銆?

---

## 5. 琛ㄨ揪寮忎笌寮曟搸

- **$EXPR** 瀛楁鏀寔澶氱琛ㄨ揪寮忓紩鎿庯紙濡?QLExpress銆丼pEL銆佽嚜瀹氫箟锛夛細

```json
{
  "$EXPR": {
    "lang": "ql", // 琛ㄨ揪寮忓紩鎿庣被鍨?
    "expr": "status == 'OFFLINE' ? 0 : range(10, 100)"
  }
}
```

- 涔熷彲鐩存帴鐢ㄥ瓧绗︿覆锛岄粯璁?QLExpress锛?

```json
{"$EXPR": "status == 'OFFLINE' ? 0 : range(10, 100)"}
```

- **鍙彃鎷旀満鍒?*锛氬疄鐜?`ExpressionEngine` 鎺ュ彛骞舵敞鍐屽埌 `ExpressionEngineRegistry`锛屽嵆鍙墿灞曟柊寮曟搸銆?

---

## 6. 澶氭潵婧愬悎骞朵笌浼樺厛绾?

- 鏀寔澶氭潵婧愶紙濡?project銆乽ser銆乼ask锛夎鍒欏悎骞躲€?
- 鍚堝苟绛栫暐锛氶珮浼樺厛绾ц鐩栦綆浼樺厛绾э紝鍚屽瓧娈?瑙勫垯浼樺厛绾ч珮鐨勭敓鏁堛€?
- 鎻愪緵澶氱鍚堝苟妯″紡锛堣鐩栥€佸悎骞躲€佷氦闆嗐€佸苟闆嗭級锛屽彲瀹氬埗銆?
- 鍐茬獊妫€娴嬶細鍙緭鍑哄啿绐佸瓧娈点€佹潵婧愩€佷紭鍏堢骇绛夎缁嗕俊鎭€?

---

## 7. 鍐茬獊妫€娴嬩笌璋冭瘯

- 姣忔鍚堝苟鍙皟鐢ㄥ啿绐佹娴嬫柟娉曪紝杈撳嚭鍐茬獊璇︽儏銆?
- 鏀寔璋冭瘯妯″紡锛坈ontext.debug=true锛夛紝璇︾粏杈撳嚭鍚堝苟銆佽В鏋愩€佹墽琛岃繃绋嬨€?
- 鍞竴 ID銆佷紭鍏堢骇銆佹潵婧愯拷韪紝渚夸簬瀹氫綅闂銆?

---

## 8. 鍙墿灞曠偣

- **琛ㄨ揪寮忓紩鎿?*锛氬疄鐜?`ExpressionEngine` 骞舵敞鍐屻€?
- **鍐呯疆鍑芥暟**锛氭墿灞?`BuiltinFunc` 鍜?`BuiltinFunctions`锛屾垨閫氳繃琛ㄨ揪寮忓紩鎿庢墿灞曘€?
- **绫诲瀷娉ㄥ唽**锛氶€氳繃 `TypeRegistry.register` 娉ㄥ唽鏂版ā鍨嬬被鍨嬨€?
- **鍚堝苟绛栫暐**锛氳嚜瀹氫箟鍚堝苟閫昏緫銆佸啿绐佸鐞嗐€?
- **鎵╁睍瀛楁**锛氶€氳繃 `extensions` 瀛楁鎵╁睍涓氬姟鍏冩暟鎹€?
- **涓婁笅鏂囧弬鏁?*锛歝ontext.parameters 鏀寔浠绘剰涓氬姟鍙傛暟銆?
- **缂撳瓨涓庡紑鍏?*锛氭敮鎸佽嚜瀹氫箟缂撳瓨绛栫暐銆佸惎鐢?绂佺敤鎺у埗銆?

---

## 9. 涓庢棫鐗堝尯鍒?

- 缁撴瀯鏇存爣鍑嗗寲锛屽瓧娈垫洿娓呮櫚锛屾敮鎸佸厓鏁版嵁鍜屾墿灞曘€?
- 鏀寔澶氱被鍨?DSL锛堢敓鎴?杩囨护/杞崲/鏍￠獙锛夛紝琛ㄨ揪鑳藉姏鏇村己銆?
- 鍚堝苟涓庝紭鍏堢骇鏈哄埗鏇村畬鍠勶紝鏀寔鍐茬獊妫€娴嬨€?
- 鍙彃鎷旇〃杈惧紡/瑙勫垯寮曟搸锛屽吋瀹瑰绉嶄笟鍔″満鏅€?
- 鍏煎鏃х増 DSL锛岃嚜鍔ㄨ浆鎹€?

---

## 10. 甯歌鐢ㄦ硶绀轰緥

### 鐢熸垚璁惧鏁版嵁

```json
{
  "unique_id": "worker_gen_001",
  "type": "generate",
  "context": {
    "MODEL": "Worker",
    "COUNT": 2
  },
  "fieldDsl": {
    "$workerId": {
      "$JOIN": [
        "worker-",
        "&.index"
      ]
    },
    "$status": {
      "$CHOICE": [
        "ONLINE",
        "OFFLINE"
      ]
    },
    "$createdTime": {
      "$EXPR": {
        "lang": "ql",
        "expr": "now('yyyy-MM-dd HH:mm:ss')"
      }
    }
  }
}
```

### 杩囨护鍦ㄧ嚎璁惧

```json
{
  "unique_id": "online_filter",
  "type": "filter",
  "fieldDsl": {
    "$status": {
      "eq": "ONLINE"
    },
    "$workerGroupId": {
      "in": [
        "us",
        "gb"
      ]
    }
  },
  "combine_dsl": {
    "battery_check": "batteryLevel >= 20"
  }
}
```

### 澶嶆潅宓屽涓庤〃杈惧紡

```json
{
  "unique_id": "complex_gen",
  "type": "generate",
  "context": {
    "MODEL": "Worker",
    "COUNT": 1
  },
  "fieldDsl": {
    "$workerId": {
      "$JOIN": [
        "worker-",
        "&.index"
      ]
    },
    "$tasks": {
      "$TYPE": "LIST",
      "$COUNT": 2,
      "$MODEL": "Task",
      "$FIELDS": {
        "$tid": {
          "$UUID": true
        },
        "$taskName": {
          "$JOIN": [
            "Task-",
            "&.index",
            "-of-Worker-",
            "&Worker.index"
          ]
        }
      }
    },
    "$onlineStrategy": {
      "$EXPR": "status == 'OFFLINE' ? 0 : range(10, 100)"
    }
  }
}
```

---

## 11. 璋冭瘯涓庡畨鍏ㄥ缓璁?

- **璋冭瘯妯″紡**锛氬缓璁紑鍙?鎺掓煡鏃跺紑鍚?context.debug=true锛岃緭鍑鸿缁嗘棩蹇椼€?
- **鍞竴 ID**锛氭瘡鏉?DSL 寤鸿鍒嗛厤鍞竴 ID锛屼究浜庤拷韪€?
- **琛ㄨ揪寮忓畨鍏?*锛氳嚜瀹氫箟琛ㄨ揪寮忓紩鎿庢椂娉ㄦ剰娉ㄥ叆椋庨櫓锛屽仛濂芥矙绠遍殧绂汇€?
- **缂撳瓨绛栫暐**锛氬悎鐞嗚缃?cacheable 鍜?cache_expire_seconds锛岄伩鍏嶇紦瀛樿剰鏁版嵁銆?
- **鍏煎鎬?*锛氬闇€鍏煎鏃?DSL锛屽彲鐢?`JsonDslParser.parseLegacyDsl` 鑷姩杞崲銆?

---

濡傞渶鏇村绀轰緥鍜屾墿灞曠敤娉曪紝璇峰弬鑰?`NewStandardDslExample.java`銆乣JsonDslMergerExample.java` 绛夌ず渚嬫枃浠躲€?

# JSON-DSL 鏍囧噯妗嗘灦鏂囨。

## 璁捐鐩爣

鏂扮殑 JSON-DSL 鏍囧噯妗嗘灦鏃ㄥ湪鎻愪緵锛?

1. **缁熶竴鐨勭粨鏋勮鑼?* - 鎵€鏈?DSL 浣跨敤鐩稿悓鐨勬爣鍑嗘牸寮?
2. **鑹ソ鐨勬墿灞曟€?* - 鏀寔鏂扮殑 DSL 绫诲瀷鍜屽鐞嗗櫒
3. **寮哄ぇ鐨勮皟璇曡兘鍔?* - 璇︾粏鐨勬棩蹇楀拰閿欒杩借釜
4. **鐏垫椿鐨勫悎骞舵満鍒?* - 鏀寔澶氭簮 DSL 鐨勪紭鍏堢骇鍚堝苟
5. **鍚戝悗鍏煎鎬?* - 鏀寔鏃ф牸寮忕殑鑷姩杞崲

## 鏍囧噯缁撴瀯

### JsonDslDefinition

```json
{
  "uniqueId": "user-generator-001",
  "type": "generate",
  "priority": 1,
  "description": "鐢熸垚鐢ㄦ埛鏁版嵁",
  "version": "1.0",
  "createTime": 1640995200000,
  "updateTime": 1640995200000,
  "context": {
    "model": "com.xa.mass.base.model.User",
    "count": 10
  },
  "fieldDsl": {
    "$name": "$RANDOM_NAME",
    "$age": "$RANDOM_INT(18, 65)",
    "$email": "$RANDOM_EMAIL"
  },
  "combineDsl": {
    "logic": "AND",
    "conditions": [
      "$age > 18",
      "$email.contains('@')"
    ]
  },
  "extensions": {
    "customField": "customValue"
  },
  "tags": [
    "user",
    "generator"
  ],
  "author": "system",
  "enabled": true,
  "cacheable": false,
  "cacheExpireSeconds": 300
}
```

### 鏍稿績瀛楁璇存槑

| 瀛楁                 | 绫诲瀷      | 蹇呭～ | 璇存槑                                        |
|--------------------|---------|----|-------------------------------------------|
| uniqueId           | String  | 鏄? | DSL 鍞竴鏍囪瘑绗?                                |
| type               | String  | 鏄? | DSL 绫诲瀷锛歡enerate/filter/transform/validate |
| priority           | Integer | 鍚? | 浼樺厛绾э紝鏁板瓧瓒婂皬浼樺厛绾ц秺楂?                            |
| description        | String  | 鍚? | DSL 鎻忚堪淇℃伅                                  |
| version            | String  | 鍚? | 鐗堟湰鍙凤紝榛樿 "1.0"                              |
| context            | Object  | 鍚? | 涓婁笅鏂囬厤缃?                                    |
| fieldDsl           | Object  | 鍚? | 瀛楁 DSL 閰嶇疆                                 |
| combineDsl         | Object  | 鍚? | 缁勫悎瑙勫垯閰嶇疆                                    |
| extensions         | Object  | 鍚? | 鎵╁睍閰嶇疆                                      |
| tags               | Array   | 鍚? | 鏍囩鍒楄〃                                      |
| author             | String  | 鍚? | 浣滆€呬俊鎭?                                     |
| enabled            | Boolean | 鍚? | 鏄惁鍚敤锛岄粯璁?true                              |
| cacheable          | Boolean | 鍚? | 鏄惁缂撳瓨锛岄粯璁?false                             |
| cacheExpireSeconds | Integer | 鍚? | 缂撳瓨杩囨湡鏃堕棿锛堢锛?                                |

### DSL 绫诲瀷

#### 1. Generate锛堢敓鎴愶級

鐢ㄤ簬鐢熸垚瀵硅薄瀹炰緥

```json
{
  "uniqueId": "user-generator",
  "type": "generate",
  "context": {
    "model": "com.xa.mass.base.model.User",
    "count": 5
  },
  "fieldDsl": {
    "$name": "$RANDOM_NAME",
    "$age": "$RANDOM_INT(18, 65)",
    "$email": "$RANDOM_EMAIL"
  }
}
```

#### 2. Filter锛堣繃婊わ級

鐢ㄤ簬杩囨护瀵硅薄鍒楄〃

```json
{
  "uniqueId": "age-filter",
  "type": "filter",
  "fieldDsl": {
    "$age": "$EXPR(age > 30)",
    "$status": "$EXPR(status == 'active')"
  },
  "combineDsl": {
    "logic": "AND"
  }
}
```

#### 3. Transform锛堣浆鎹級

鐢ㄤ簬杞崲瀵硅薄鏍煎紡

```json
{
  "uniqueId": "user-transform",
  "type": "transform",
  "fieldDsl": {
    "$fullName": "$EXPR(firstName + ' ' + lastName)",
    "$ageGroup": "$EXPR(age < 30 ? 'young' : age < 50 ? 'middle' : 'senior')"
  }
}
```

#### 4. Validate锛堟牎楠岋級

鐢ㄤ簬鏍￠獙瀵硅薄鏈夋晥鎬?

```json
{
  "uniqueId": "user-validate",
  "type": "validate",
  "fieldDsl": {
    "$email": "$EXPR(email.matches('^[^@]+@[^@]+\\.[^@]+$'))",
    "$age": "$EXPR(age >= 0 && age <= 150)"
  }
}
```

## 琛ㄨ揪寮忓紩鎿?

### 鏀寔鐨勮〃杈惧紡绫诲瀷

1. **鍐呯疆鍑芥暟**锛歚$RANDOM_NAME`, `$RANDOM_INT(1, 100)`, `$RANDOM_EMAIL`
2. **QLExpress 琛ㄨ揪寮?*锛歚$EXPR(age > 30 && status == 'active')`
3. **鑷畾涔夊嚱鏁?*锛氶€氳繃鎵╁睍鐐规敞鍐?

### 琛ㄨ揪寮忚娉?

```json
{
  "fieldDsl": {
    "$simpleField": "$RANDOM_NAME",
    "$complexField": "$EXPR(age > 30 && (status == 'active' || status == 'pending'))",
    "$calculatedField": "$EXPR(firstName + ' ' + lastName)"
  }
}
```

## 鍚堝苟鍜屼紭鍏堢骇

### 鍚堝苟绛栫暐

- **楂樹紭鍏堢骇瑕嗙洊浣庝紭鍏堢骇**锛氱浉鍚屽瓧娈碉紝楂樹紭鍏堢骇 DSL 瑕嗙洊浣庝紭鍏堢骇
- **瀛楁绾у悎骞?*锛氫笉鍚屽瓧娈佃繘琛屽悎骞?
- **鍐茬獊妫€娴?*锛氭娴嬪苟鎶ュ憡鍚堝苟鍐茬獊

### 绀轰緥

```java
// 浣庝紭鍏堢骇 DSL
JsonDslDefinition lowPriority = new JsonDslDefinition("low", DslType.FILTER);
lowPriority.setPriority(10);
lowPriority.setFieldDsl(Map.of("$age", "$EXPR(age > 20)"));

// 楂樹紭鍏堢骇 DSL
JsonDslDefinition highPriority = new JsonDslDefinition("high", DslType.FILTER);
highPriority.setPriority(1);
highPriority.setFieldDsl(Map.of("$age", "$EXPR(age > 30)"));

// 鍚堝苟缁撴灉锛歛ge 瀛楁浣跨敤楂樹紭鍏堢骇鐨勬潯浠?(age > 30)
JsonDslDefinition merged = JsonDslMerger.merge(Arrays.asList(lowPriority, highPriority));
```

## 鍐茬獊妫€娴?

### 鍐茬獊绫诲瀷

1. **瀛楁鍐茬獊**锛氱浉鍚屽瓧娈电殑涓嶅悓瑙勫垯
2. **閫昏緫鍐茬獊**锛氱浉浜掔煕鐩剧殑缁勫悎閫昏緫
3. **绫诲瀷鍐茬獊**锛氫笉鍏煎鐨?DSL 绫诲瀷

### 鍐茬獊澶勭悊

```java
MergeResult result = JsonDslMerger.mergeWithConflictDetection(dslList);
if (result.hasConflicts()) {
    System.out.println("妫€娴嬪埌鍐茬獊锛?);
    result.getConflicts().forEach(System.out::println);
}
```

## 鎵╁睍鐐?

### 1. 琛ㄨ揪寮忓紩鎿庢墿灞?

```java
public interface ExpressionEngine {
    Object evaluate(String expression, Map<String, Object> context);
    boolean supports(String expression);
}

// 娉ㄥ唽鑷畾涔夎〃杈惧紡寮曟搸
ExpressionEngineRegistry.register("custom", new CustomExpressionEngine());
```

### 2. 鍐呯疆鍑芥暟鎵╁睍

```java
public interface BuiltinFunction {
    Object execute(Object... args);

    String getName();
}

// 娉ㄥ唽鑷畾涔夊嚱鏁?
BuiltinFunctionRegistry.

register(new CustomFunction());
```

### 3. 绫诲瀷娉ㄥ唽鎵╁睍

```java
// 娉ㄥ唽鑷畾涔夌被鍨?
TypeRegistry.register("CustomType", CustomType.class);
```

### 4. 鍚堝苟绛栫暐鎵╁睍

```java
public interface MergeStrategy {
    JsonDslDefinition merge(List<JsonDslDefinition> dslList);
}

// 娉ㄥ唽鑷畾涔夊悎骞剁瓥鐣?
MergeStrategyRegistry.register("custom", new CustomMergeStrategy());
```

### 5. 鎵╁睍瀛楁

```json
{
  "extensions": {
    "customProcessor": "CustomProcessorClass",
    "customConfig": {
      "key": "value"
    }
  }
}
```

### 6. 涓婁笅鏂囧弬鏁?

```java
ProcessingContext context = new ProcessingContext();
context.

setParameter("customParam","value");
context.

setVariable("customVar","value");
```

### 7. 缂撳瓨鎵╁睍

```json
{
  "cacheable": true,
  "cacheExpireSeconds": 600,
  "cacheKey": "custom-cache-key"
}
```

## 涓庢棫 DSL 鐨勫樊寮?

### 涓昏鏀硅繘

1. **缁熶竴缁撴瀯**锛氭墍鏈?DSL 浣跨敤鐩稿悓鐨勬爣鍑嗘牸寮?
2. **绫诲瀷瀹夊叏**锛氭槑纭殑 DSL 绫诲瀷瀹氫箟
3. **浼樺厛绾ф敮鎸?*锛氬唴缃紭鍏堢骇鏈哄埗
4. **鎵╁睍鎬?*锛氫赴瀵岀殑鎵╁睍鐐?
5. **璋冭瘯鏀寔**锛氳缁嗙殑鏃ュ織鍜岄敊璇俊鎭?
6. **鍚戝悗鍏煎**锛氭敮鎸佹棫鏍煎紡鑷姩杞崲

### 杩佺Щ鎸囧崡

```java
// 鏃ф柟寮?
String legacyFormat = "{ "
$name": "$RANDOM_NAME" }";
List<Object> result = JsonDslEngine.generateList(legacyFormat);

// 鏂版柟寮?
JsonDslDefinition dsl = new JsonDslDefinition("generator", DslType.GENERATE);
dsl.

setFieldDsl(Map.of("$name", "$RANDOM_NAME"));
Object result = JsonDslProcessorEngine.process(dsl);
```

鏂扮殑澶勭悊鍣ㄦ灦鏋勬彁渚涗簡鏇村ソ鐨勮璁°€佹洿寮虹殑鎵╁睍鎬у拰鏇存竻鏅扮殑浠ｇ爜缁撴瀯锛屾槸 DSL 妗嗘灦鐨勯噸瑕佹敼杩涖€?

### 6. 寮虹被鍨嬪鐞嗗櫒鎺ュ彛涓庡紓甯搁鏍硷紙2024骞?鏈堟洿鏂帮級

#### 鏂规硶娉涘瀷鎺ュ彛

鏂扮増鎵€鏈夊己绫诲瀷澶勭悊鍣ㄦ帴鍙ｏ紙GenerateProcessor銆丗ilterProcessor銆乀ransformProcessor銆乂alidateProcessor锛夊潎閲囩敤"鏂规硶娉涘瀷"
锛屼笉鍐嶄娇鐢ㄧ被娉涘瀷銆備緥濡傦細

```java
public interface GenerateProcessor extends JsonDslProcessor {
    <T> List<T> generate(JsonDslDefinition definition, ProcessingContext context, Class<T> targetType);
}

public interface FilterProcessor extends JsonDslProcessor {
    <T> List<T> filter(List<T> input, JsonDslDefinition definition, ProcessingContext context);
}

public interface TransformProcessor extends JsonDslProcessor {
    <T> T transform(T input, JsonDslDefinition definition, ProcessingContext context);
}

public interface ValidateProcessor extends JsonDslProcessor {
    <T> List<String> validate(T input, JsonDslDefinition definition, ProcessingContext context);
}
```

杩欐牱鍚屼竴涓鐞嗗櫒瀹炰緥鍙鐞嗕换鎰忕被鍨嬪璞★紝鎻愬崌浜嗗鐢ㄦ€у拰鐏垫椿鎬с€?

#### 缁熶竴寮傚父椋庢牸

鎵€鏈?DSL 鐩稿叧鍙傛暟鏍￠獙銆佸鐞嗛敊璇潎搴旀姏鍑?`JsonDslException`锛屼笉鍐嶆姏 `IllegalArgumentException`銆傚锛?

```java
if (definition == null) {
    throw new JsonDslException("Definition cannot be null");
}
```

#### 鍗囩骇涓庡吋瀹规€?

- 鏃т唬鐮佸鏈?`GenerateProcessor<T>`銆乣DefaultGenerateProcessor<T>` 绛夌被娉涘瀷澹版槑锛岄渶鍗囩骇涓烘棤绫绘硾鍨嬶紝鏂规硶绛惧悕鐢?`<T>`銆?
- 澶勭悊鍣ㄦ敞鍐屻€佽幏鍙栥€侀摼寮忚皟鐢ㄧ瓑 API 涓嶅彉銆?

#### 绀轰緥锛氬己绫诲瀷閾惧紡澶勭悊

```java
GenerateProcessor generateProcessor = new DefaultGenerateProcessor();
FilterProcessor filterProcessor = new DefaultFilterProcessor();

List<User> users = generateProcessor.generate(dsl, context, User.class);
List<User> filtered = filterProcessor.filter(users, filterDsl, context);
```

#### 绀轰緥锛氬紓甯告崟鑾?

```java
try{
        generateProcessor.generate(null,context, User .class);
}catch(
JsonDslException e){
        // 澶勭悊鍙傛暟鏍￠獙寮傚父
        }
```

---

## 鏂扮殑鏍囧噯 DSL 妗嗘灦璇存槑涓庨棶棰樿褰?

### 1. 鑷姩娉ㄥ唽鏈哄埗

- 鎵€鏈夊唴缃嚱鏁帮紙濡?`$choice`銆乣$range`銆乣$join` 绛夛級鍦?`BuiltinFunctions` 鐨?static 鍧椾腑娉ㄥ唽鍒?`FUNCTION_MAP`銆?
- `BuiltinFunctions` 鎻愪緵缁熶竴鐨勬敞鍐岃〃绠＄悊锛岄伩鍏嶉噸澶嶆敞鍐屻€?
- QLExpress 娉ㄥ唽鏃惰嚜鍔ㄦ帓闄ゅ唴缃搷浣滅锛堝 `in`銆乣eq`銆乣gte` 绛夛級锛岄伩鍏嶅啿绐併€?

### 2. mock/琛ㄨ揪寮?绫诲瀷閫傞厤

- 鎵€鏈?mock 鐢熸垚銆乫ilter銆佽〃杈惧紡绛夌粺涓€璧?`TemplateValueResolver` + `BuiltinFunctions`銆?
- 绫诲瀷閫傞厤缁熶竴璧?`TypeAdapterUtil.adaptType`锛屾敮鎸佸瓧绗︿覆銆佹暟瀛椼€佷笅鏍囥€乥oolean鈫掓灇涓剧瓑甯歌鍦烘櫙銆?
- boolean鈫掓灇涓炬敮鎸佹櫤鑳芥槧灏勶紙濡?true鈫扥NLINE/ENABLED/YES锛宖alse鈫扥FFLINE/DISABLED/NO锛夛紝鍚﹀垯 fallback 涓虹涓€涓父閲忋€?

### 3. 娴嬭瘯闅旂涓庡叏灞€鐘舵€?

- 鍗曟祴鏃讹紝`BuiltinFunctions` 绛?static 娉ㄥ唽琛ㄥ彲鑳借鍏跺畠娴嬭瘯姹℃煋锛屽鑷存敞鍐岀己澶辨垨 mock 澶辫触銆?
- 瑙ｅ喅鏂规锛氭瘡涓祴璇曠敤渚嬪墠鍚庢竻鐞嗘敞鍐岃〃锛屽苟寮哄埗瑙﹀彂 `BuiltinFunctions` static 鍧楋紝淇濊瘉娉ㄥ唽涓€鑷存€с€?
- 浣嗗叏閲忔祴璇曟椂锛屼粛鍙兘鍥犲叾瀹冩祴璇曠敤渚嬬殑 DSL/mock 瑙勫垯姹℃煋瀵艰嚧閮ㄥ垎鐢ㄤ緥琛ㄧ幇寮傚父銆?

### 4. 閬楃暀/寰呮帓鏌ラ棶棰樼偣

- 鍏ㄩ噺娴嬭瘯鏃讹紝`NewStandardDslTypeRegistrationTest` 浠嶅伓鍙?`$CHOICE` 鏈閫掑綊鎵ц锛宮ock 缁撴灉涓哄師濮?Map锛屽鑷寸被鍨嬮€傞厤寮傚父銆?
- `QLExpressBuiltinTest` 鍙兘鍥犳敞鍐岃〃鏈強鏃舵敞鍐?`range` 绛夊嚱鏁板鑷磋〃杈惧紡鎵句笉鍒般€?
- 鐩墠閫氳繃鍦?`@BeforeEach` 寮哄埗瑙﹀彂 static 鍧楀彲缂撹В锛屼絾鏍瑰洜鍙兘鏄敞鍐岃〃/DSL/mock 瑙勫垯鍏ㄥ眬姹℃煋锛岄渶杩涗竴姝ュ交鏌ャ€?

### 5. 寤鸿涓庡悗缁柟鍚?

- 鍚庣画鍙€冭檻灏嗘敞鍐岃〃/鍐呯疆鍑芥暟娉ㄥ唽褰诲簳涓庢祴璇曠敤渚嬭В鑰︼紝鎴栨瘡娆?mock/琛ㄨ揪寮忓墠鑷姩妫€娴嬪苟琛ユ敞鍐屻€?
- 鍙鍔犺缁嗘棩蹇楋紝杈呭姪瀹氫綅鍏ㄥ眬鐘舵€佹薄鏌撴潵婧愩€?
- 缁х画浼樺寲绫诲瀷閫傞厤鍜?mock 閫掑綊閫昏緫锛屼繚璇佹墍鏈夊満鏅笅 mock 缁撴灉涓庨鏈熶竴鑷淬€?

---

濡傞渶鍒囨崲璇濋鎴栫户缁帓鏌ワ紝寤鸿鍏堝弬鑰冩湰鏂囨。锛屽悗缁彲鐩存帴鍦ㄦ鍩虹涓婄户缁帹杩涖€?

## 6. 鏈疆浼氳瘽鏍稿績淇敼鐐瑰悓姝?

- 绠€鍖栨敞鍐屾満鍒讹細缁熶竴浣跨敤 BuiltinFunctions 涓殑 FUNCTION_MAP 鍜?OPERATOR_MAP锛岀Щ闄ら噸澶嶇殑娉ㄥ唽琛ㄣ€?
- TemplateValueResolver 绠€鍖栵細绉婚櫎 BUILTIN_RESOLVERS锛岀洿鎺ヤ娇鐢?BuiltinFunctions.eval()銆?
- $range 娉ㄥ唽閫昏緫淇锛歮ock 鏃惰繑鍥炲尯闂村唴鍗曚釜闅忔満 int锛岃€屼笉鏄?List銆?
- TypeAdapterUtil.adaptType 澧炲己锛氭敮鎸?boolean鈫掓灇涓炬櫤鑳芥槧灏勶紝鍏煎鍘嗗彶 mock 琛屼负銆?
- BuiltinFunctions.registerToQLExpress 澧炲己锛氳嚜鍔ㄦ帓闄ゆ墍鏈夊唴缃搷浣滅锛屽交搴曢槻姝?in/eq/gte 绛夊啿绐併€?
- 澧炲姞闃插尽鎷︽埅鍜岃缁嗘棩蹇楋紝杈呭姪瀹氫綅娉ㄥ唽鍐茬獊銆?
- 娴嬭瘯鐢ㄤ緥锛堝 NewStandardDslTypeRegistrationTest銆丵LExpressBuiltinTest锛夊鍔?@BeforeEach 寮哄埗瑙﹀彂 BuiltinFunctions static
  鍧楋紝淇濊瘉姣忔娴嬭瘯鍓嶆敞鍐岃〃涓€鑷淬€?
- mockFromDsl 鍏煎鏂?DSL 缁撴瀯锛氶《灞傛棤 MODEL 瀛楁鏃惰嚜鍔ㄤ粠 context.MODEL 鍙栧€笺€?
- 鏂囨。鍚屾鏇存柊锛岃褰曟墍鏈夋満鍒躲€侀棶棰樼偣涓庡缓璁€?

--- 

# JSON-DSL 鏂拌娉曡鏄?

## 1. 鍐呯疆鍑芥暟鍙傛暟鏀寔鍗曞紩鍙?

- 鐜板湪 DSL 鏀寔鍦?$CHOICE銆?JOIN銆?RANGE 绛夊嚱鏁板弬鏁颁腑浣跨敤鍗曞紩鍙峰寘瑁瑰瓧绗︿覆鎴栧垪琛ㄥ厓绱犮€?
- 渚嬪锛?

```json
{
  "name": "$choice('Alice', 'Bob', 'Charlie')",
  "status": "$choice('active', 'inactive', 'pending')",
  "email": "$join('alice', '@', 'example.com')"
}
```

- 涔熸敮鎸佽€佸啓娉曪紙涓嶅姞寮曞彿锛夛細

```json
{
  "name": "$CHOICE(Alice, Bob, Charlie)",
  "status": "$CHOICE(active, inactive, pending)",
  "email": "$JOIN(alice, @, example.com)"
}
```

- 涓ょ鍐欐硶閮藉吋瀹广€?

## 2. 琛ㄨ揪寮忓啓娉?

- 琛ㄨ揪寮忓缓璁敤 `{ "$EXPR": "琛ㄨ揪寮忓唴瀹? }` 褰㈠紡銆?
- 渚嬪锛?

```json
{
  "age": { "$EXPR": "range(18, 65)" },
  "score": { "$EXPR": "score > 80" }
}
```

## 3. 鍏朵粬璇存槑

- 鏀寔宓屽銆侀摼寮忚皟鐢ㄣ€?
- 璇﹁娴嬭瘯鐢ㄤ緥鍜岀ず渚嬨€?

## 4. 鍙傛暟椋庢牸鍏煎璇存槑

- 鏀寔濡備笅鍐欐硶锛?
    - `$choice('A', 'B', 'C')`
    - `$CHOICE(A, B, C)`
    - `$join('a', 'b', 'c')`
    - `$JOIN(a, b, c)`
    - `$range(1, 100)`
    - `$RANGE(1, 100)`
- 鎺ㄨ崘琛ㄨ揪寮忕敤 `{ "$EXPR": "score > 80" }`銆?
- 浠ヤ笂鎵€鏈夐鏍煎潎鍙贩鐢紝璇﹁娴嬭瘯鐢ㄤ緥銆?

### 渚嬪瓙

```json
{
  "name": "$choice('Alice', 'Bob')",
  "status": "$CHOICE(active, inactive)",
  "email": "$join('alice', '@', 'example.com')",
  "score": { "$EXPR": "range(60, 100)" }
}
``` 