# 鏍囧噯鍖?JSON-DSL 璁捐鏂囨。

## 璁捐鐞嗗康

### 闂鑳屾櫙

鍘熸湁鐨?JSON-DSL 缁撴瀯杩囦簬绠€鍗曪紝缂轰箯锛?

- 缁熶竴鐨勬爣璇嗗拰杩借釜鏈哄埗
- 绫诲瀷鍖哄垎鍜屼紭鍏堢骇鎺у埗
- 鍏冩暟鎹鐞嗗拰鏂囨。鍖栨敮鎸?
- 鎵╁睍鎬у拰鍏煎鎬т繚闅?
- 璋冭瘯鍜岄棶棰樻帓鏌ヨ兘鍔?

### 璁捐鐩爣

1. **鏍囧噯鍖栫粨鏋?* - 瀹氫箟鏄庣‘鐨勭粨鏋勪綋锛屼究浜庣悊瑙ｅ拰缁存姢
2. **鍚戝悗鍏煎** - 鏀寔浼犵粺 DSL 鏍煎紡锛屽钩婊戣縼绉?
3. **鍙墿灞曟€?* - 棰勭暀鎵╁睍瀛楁锛屾敮鎸佹湭鏉ュ姛鑳?
4. **鍙拷韪€?* - 鎻愪緵鍞竴鏍囪瘑鍜屽厓鏁版嵁锛屼究浜庤皟璇?
5. **绫诲瀷瀹夊叏** - 閫氳繃绫诲瀷鏋氫妇纭繚 DSL 绫诲瀷姝ｇ‘鎬?

## 鏍稿績缁撴瀯

### 鏍囧噯鍖?DSL 鏍煎紡

```json
{
  "unique_id": "dsl_identifier",
  "type": "generate|filter|transform|validate",
  "priority": 1,
  "desc": "DSL 鎻忚堪淇℃伅",
  "version": "1.0",
  "create_time": 1640995200000,
  "update_time": 1640995200000,
  "context": {
    "MODEL": "class_name",
    "COUNT": 1,
    "TYPE": "LIST|SET|MAP",
    "scope_name": "scope_name",
    "parent_scope": "parent_scope",
    "parameters": {},
    "debug": false,
    "strict": false
  },
  "fieldDsl": {
    "field_name": "field_value_or_function"
  },
  "combine_dsl": {
    "rule_id": "rule_expression"
  },
  "extensions": {
    "extension_key": "extension_value"
  },
  "tags": [
    "tag1",
    "tag2"
  ],
  "author": "author_name",
  "enabled": true,
  "cacheable": false,
  "cache_expire_seconds": 300
}
```

### 瀛楁璇存槑

#### 鏍稿績瀛楁

| 瀛楁          | 绫诲瀷      | 蹇呴渶 | 璇存槑                                        |
|-------------|---------|----|-------------------------------------------|
| `unique_id` | String  | 鏄? | DSL 鍞竴鏍囪瘑绗︼紝鐢ㄤ簬璋冭瘯鍜岀紦瀛?                        |
| `type`      | String  | 鏄? | DSL 绫诲瀷锛歡enerate/filter/transform/validate |
| `priority`  | Integer | 鍚? | 鎵ц浼樺厛绾э紝鏁板瓧瓒婂皬浼樺厛绾ц秺楂?                          |
| `desc`      | String  | 鍚? | DSL 鎻忚堪淇℃伅锛岀敤浜庢枃妗ｅ寲                            |
| `version`   | String  | 鍚? | 鐗堟湰鍙凤紝鐢ㄤ簬鍏煎鎬ф帶鍒?                              |

#### 鏃堕棿瀛楁

| 瀛楁            | 绫诲瀷   | 璇存槑      |
|---------------|------|---------|
| `create_time` | Long | 鍒涘缓鏃堕棿鎴?  |
| `update_time` | Long | 鏈€鍚庝慨鏀规椂闂存埑 |

#### 閰嶇疆瀛楁

| 瀛楁            | 绫诲瀷     | 璇存槑                     |
|---------------|--------|------------------------|
| `context`     | Object | 涓婁笅鏂囬厤缃紝鍖呭惈 MODEL銆丆OUNT 绛?|
| `fieldDsl`    | Object | 瀛楁 DSL 閰嶇疆锛屽畾涔夊悇瀛楁鐢熸垚瑙勫垯    |
| `combine_dsl` | Object | 缁勫悎瑙勫垯閰嶇疆锛屾敮鎸佸瀛楁鑱斿悎鍒ゆ柇       |
| `extensions`  | Object | 鎵╁睍閰嶇疆锛岀敤浜庢湭鏉ュ姛鑳芥墿灞?         |

#### 鍏冩暟鎹瓧娈?

| 瀛楁                     | 绫诲瀷      | 璇存槑               |
|------------------------|---------|------------------|
| `tags`                 | Array   | 鏍囩鍒楄〃锛岀敤浜庡垎绫诲拰绛涢€?    |
| `author`               | String  | 浣滆€呬俊鎭?            |
| `enabled`              | Boolean | 鏄惁鍚敤锛岄粯璁や负 true    |
| `cacheable`            | Boolean | 鏄惁缂撳瓨缁撴灉锛岄粯璁や负 false |
| `cache_expire_seconds` | Integer | 缂撳瓨杩囨湡鏃堕棿锛堢锛?       |

## DSL 绫诲瀷

### 1. GENERATE锛堢敓鎴愶級

鐢ㄤ簬鐢熸垚瀵硅薄瀹炰緥

```json
{
  "unique_id": "device_generator",
  "type": "generate",
  "context": {
    "MODEL": "Device",
    "COUNT": 3
  },
  "fieldDsl": {
    "deviceId": {
      "$JOIN": [
        "device-",
        "&.index"
      ]
    },
    "status": {
      "$CHOICE": [
        "ONLINE",
        "OFFLINE"
      ]
    }
  }
}
```

### 2. FILTER锛堣繃婊わ級

鐢ㄤ簬杩囨护瀵硅薄鍒楄〃

```json
{
  "unique_id": "online_device_filter",
  "type": "filter",
  "fieldDsl": {
    "status": {
      "eq": "ONLINE"
    },
    "deviceGroupId": {
      "in": [
        "us",
        "gb"
      ]
    }
  }
}
```

### 3. TRANSFORM锛堣浆鎹級

鐢ㄤ簬杞崲瀵硅薄缁撴瀯

```json
{
  "unique_id": "device_transformer",
  "type": "transform",
  "fieldDsl": {
    "deviceId": {"$UPPER": "&.deviceId"},
    "status": {"$MAP": {"ONLINE": "active", "OFFLINE": "inactive"}}
  }
}
```

### 4. VALIDATE锛堥獙璇侊級

鐢ㄤ簬楠岃瘉瀵硅薄鏈夋晥鎬?

```json
{
  "unique_id": "device_validator",
  "type": "validate",
  "fieldDsl": {
    "deviceId": {"required": true, "pattern": "^device-\\d+$"},
    "status": {"enum": ["ONLINE", "OFFLINE"]}
  }
}
```

## 涓婁笅鏂囬厤缃?

### context 瀛楁璇﹁В

```json
{
  "context": {
    "MODEL": "Device",           // 妯″瀷绫诲悕鎴栨敞鍐屽埆鍚?
    "COUNT": 3,                  // 鐢熸垚鏁伴噺锛岄粯璁?1
    "TYPE": "LIST",              // 闆嗗悎绫诲瀷锛歀IST/SET/MAP
    "scope_name": "Device",      // 浣滅敤鍩熷悕绉?
    "parent_scope": "Parent",    // 鐖朵綔鐢ㄥ煙寮曠敤
    "parameters": {              // 棰濆鍙傛暟
      "env": "dev",
      "region": "us"
    },
    "debug": false,              // 璋冭瘯妯″紡
    "strict": true               // 涓ユ牸妯″紡
  }
}
```

## 缁勫悎瑙勫垯

### combine_dsl 瀛楁璇﹁В

鏀寔澶氬瓧娈佃仈鍚堝垽鏂拰澶嶆潅涓氬姟閫昏緫锛?

```json
{
  "combine_dsl": {
    "status_group_rule": "status == 'ONLINE' ? deviceGroupId : 'unknown'",
    "version_check_rule": "agentVersion.startsWith('1.0') ? 'stable' : 'beta'",
    "capacity_rule": "deviceGroupId == 'us' ? 100 : deviceGroupId == 'gb' ? 50 : 30"
  }
}
```

## 鍚戝悗鍏煎

### 浼犵粺 DSL 鏍煎紡鏀寔

绯荤粺鑷姩璇嗗埆浼犵粺鏍煎紡骞惰浆鎹负鏍囧噯鍖栨牸寮忥細

**浼犵粺鏍煎紡锛?*

```json
{
  "MODEL": "Device",
  "COUNT": 3,
  "FIELDS": {
    "deviceId": {"$JOIN": ["device-", "&.index"]},
    "status": {"$CHOICE": ["ONLINE", "OFFLINE"]}
  }
}
```

**鑷姩杞崲涓猴細**

```json
{
  "unique_id": "legacy_1640995200000",
  "type": "generate",
  "desc": "浠庝紶缁?DSL 缁撴瀯杞崲",
  "version": "1.0",
  "context": {
    "MODEL": "Device",
    "COUNT": 3
  },
  "fieldDsl": {
    "deviceId": {
      "$JOIN": [
        "device-",
        "&.index"
      ]
    },
    "status": {
      "$CHOICE": [
        "ONLINE",
        "OFFLINE"
      ]
    }
  }
}
```

## 浣跨敤绀轰緥

### 1. 鍒涘缓鏍囧噯鍖?DSL

```java
// 鍒涘缓 DSL 瀹氫箟
JsonDslDefinition definition = new JsonDslDefinition("my_device_generator", JsonDslDefinition.DslType.GENERATE);
definition.

setDescription("鐢熸垚娴嬭瘯璁惧鏁版嵁");
definition.

setAuthor("test_user");
definition.

setTags(new String[] {
    "device", "test"
});

// 璁剧疆涓婁笅鏂?
JsonDslContext context = new JsonDslContext("Device", 3);
context.

setScopeName("Device");
definition.

setContext(context);

// 璁剧疆瀛楁 DSL
definition.

setFieldDsl(Map.of(
        "deviceId", Map.of("$JOIN", List.of("device-", "&.index")),
        "status",Map.

of("$CHOICE",List.of("ONLINE", "OFFLINE"))
        ));

// 楠岃瘉
        definition.

validate();
```

### 2. 瑙ｆ瀽 DSL

```java
// 瑙ｆ瀽鏍囧噯鍖?DSL
String jsonDsl = "..."; // 鏍囧噯鍖?DSL JSON
JsonDslDefinition definition = JsonDslParser.parse(jsonDsl);

// 杞崲涓轰紶缁熸牸寮忓苟鐢熸垚鏁版嵁
String legacyFormat = JsonDslParser.toJson(definition);
List<Device> devices = JsonDslEngine.generateList(legacyFormat, Device.class);
```

### 3. 鍚戝悗鍏煎浣跨敤

```java
// 鐩存帴浣跨敤浼犵粺鏍煎紡锛堣嚜鍔ㄨ浆鎹級
String legacyDsl = """
                {
                  "MODEL": "Device",
                  "COUNT": 3,
                  "FIELDS": {
                    "deviceId": {"$JOIN": ["device-", "&.index"]}
                  }
                }
                """;

List<Device> devices = JsonDslEngine.generateList(legacyDsl, Device.class);
```

## 鎵╁睍鏈哄埗

### 1. 鎵╁睍瀛楁

閫氳繃 `extensions` 瀛楁鏀寔鏈潵鍔熻兘鎵╁睍锛?

```json
{
  "extensions": {
    "business_rules": {
      "max_devices": 100,
      "preferred_groups": ["us", "gb"]
    },
    "performance": {
      "cache_strategy": "lru",
      "batch_size": 50
    }
  }
}
```

### 2. 鑷畾涔?DSL 绫诲瀷

閫氳繃鎵╁睍 `DslType` 鏋氫妇鏀寔鏂扮殑 DSL 绫诲瀷锛?

```java
public enum DslType {
    GENERATE("generate", "瀵硅薄鐢熸垚"),
    FILTER("filter", "瀵硅薄杩囨护"),
    TRANSFORM("transform", "瀵硅薄杞崲"),
    VALIDATE("validate", "瀵硅薄楠岃瘉"),
    CUSTOM("custom", "鑷畾涔夌被鍨?); // 鏂板绫诲瀷
}
```

## 鏈€浣冲疄璺?

### 1. 鍛藉悕瑙勮寖

- `unique_id`: 浣跨敤鏈夋剰涔夌殑鏍囪瘑绗︼紝濡?`device_generator_v1`
- `desc`: 鎻愪緵娓呮櫚鐨勬弿杩颁俊鎭?
- `tags`: 浣跨敤涓€鑷寸殑鏍囩浣撶郴

### 2. 鐗堟湰绠＄悊

- 浣跨敤璇箟鍖栫増鏈彿
- 鍦?`desc` 涓褰曞彉鏇翠俊鎭?
- 閫氳繃 `version` 瀛楁鎺у埗鍏煎鎬?

### 3. 缂撳瓨绛栫暐

- 瀵逛簬棰戠箒浣跨敤鐨?DSL锛岃缃?`cacheable: true`
- 鏍规嵁鏁版嵁鏇存柊棰戠巼璁剧疆鍚堥€傜殑 `cache_expire_seconds`
- 鍦ㄨ皟璇曟椂璁剧疆 `debug: true`

### 4. 閿欒澶勭悊

- 浣跨敤 `validate()` 鏂规硶楠岃瘉 DSL 瀹氫箟
- 閫氳繃 `unique_id` 杩借釜闂
- 鍒╃敤 `desc` 鍜?`tags` 鎻愪緵涓婁笅鏂囦俊鎭?

## 杩佺Щ鎸囧崡

### 浠庝紶缁熸牸寮忚縼绉?

1. **娓愯繘寮忚縼绉?*锛氱郴缁熸敮鎸佷紶缁熸牸寮忥紝鍙互閫愭杩佺Щ
2. **鑷姩杞崲**锛氫娇鐢?`JsonDslParser.parse()` 鑷姩杞崲
3. **鎵嬪姩浼樺寲**锛氭牴鎹笟鍔￠渶姹傛坊鍔犲厓鏁版嵁鍜屾墿灞曞瓧娈?

### 杩佺Щ姝ラ

1. 涓虹幇鏈?DSL 娣诲姞 `unique_id` 鍜?`type` 瀛楁
2. 灏?`FIELDS` 閲嶅懡鍚嶄负 `fieldDsl`
3. 灏?`MODEL`銆乣COUNT` 绛夌Щ鍒?`context` 涓?
4. 娣诲姞鎻忚堪鎬у瓧娈碉紙`desc`銆乣author`銆乣tags`锛?
5. 鏍规嵁闇€瑕佹坊鍔犳墿灞曞瓧娈?

## 鎬荤粨

鏍囧噯鍖?DSL 缁撴瀯鎻愪緵浜嗭細

- **鏇村ソ鐨勫彲缁存姢鎬?*锛氭槑纭殑缁撴瀯鍜屽厓鏁版嵁
- **鏇村己鐨勬墿灞曟€?*锛氶鐣欐墿灞曞瓧娈靛拰绫诲瀷绯荤粺
- **鏇撮珮鐨勫彲杩借釜鎬?*锛氬敮涓€鏍囪瘑鍜岃皟璇曚俊鎭?
- **瀹屾暣鐨勫吋瀹规€?*锛氬悜鍚庡吋瀹逛紶缁熸牸寮?
- **涓板瘜鐨勫姛鑳?*锛氭敮鎸佸绉?DSL 绫诲瀷鍜岀粍鍚堣鍒?

杩欎釜璁捐鏃繚鎸佷簡鍘熸湁 DSL 鐨勭畝娲佹€э紝鍙堟彁渚涗簡浼佷笟绾у簲鐢ㄦ墍闇€鐨勬爣鍑嗗寲鍜屽彲鎵╁睍鎬с€?