package com.ibm.research.reefer.simulator;

import java.util.HashMap;
import java.util.Map;

import javax.json.Json;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonValue;

import com.ibm.research.kar.Kar;
import com.ibm.research.kar.actor.annotations.Activate;
import com.ibm.research.kar.actor.annotations.Actor;
import com.ibm.research.kar.actor.annotations.Deactivate;
import com.ibm.research.kar.actor.annotations.Remote;

/**
 * An actor that implements a persistent Map
 */
@Actor
public class SimulatorHelper extends BaseActor {

//	private static Object toValue(Response response) {
//		if (response.hasEntity()) {
//			MediaType type = response.getMediaType();
//			if (type.equals(MediaType.APPLICATION_JSON_TYPE)) {
//				return response.readEntity(JsonValue.class);
//			} else if (type.equals(MediaType.TEXT_PLAIN_TYPE)) {
//				return response.readEntity(String.class);
//			} else {
//				return JsonValue.NULL;
//			}
//		} else {
//			return JsonValue.NULL;
//		}
//	}

  @Activate
  public void initState() {
  }

  @Deactivate
  public void saveState() {
  }

  @Remote
  public JsonValue get(JsonValue key) {
    return Kar.Actors.State.get(this, ((JsonString) key).getString());
  }

  @Remote
  public JsonValue set(JsonValue key, JsonValue value) {
    int n = Kar.Actors.State.set(this, ((JsonString) key).getString(), value);
    return Json.createValue(n);
  }

  @Remote
  public JsonValue del(JsonValue key) {//int n = actorDeleteState(this, ((JsonString) key).getString());
    int n = Kar.Actors.State.remove(this, ((JsonString) key).getString());
    return Json.createValue(n);
  }

  @Remote
  public JsonValue getAll() {
    Map<String, JsonValue> tempMap = new HashMap<String, JsonValue>();
    tempMap.putAll(Kar.Actors.State.getAll(this));


    JsonObjectBuilder builder = Json.createObjectBuilder();
    tempMap.forEach(builder::add);
    return builder.build();
  }
}
