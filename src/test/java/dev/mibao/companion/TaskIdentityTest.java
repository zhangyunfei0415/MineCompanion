package dev.mibao.companion;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import org.junit.jupiter.api.Test;

class TaskIdentityTest {
  @Test
  void nestedKeyOrderDoesNotCreateAnotherIntent() {
    var a =
        JsonParser.parseString("{\"item\":\"iron\",\"target\":{\"x\":1,\"y\":2}}")
            .getAsJsonObject();
    var b =
        JsonParser.parseString("{\"target\":{\"y\":2,\"x\":1},\"item\":\"iron\"}")
            .getAsJsonObject();
    assertEquals(TaskIdentity.signature("restock", a), TaskIdentity.signature("restock", b));
  }

  @Test
  void quantityAndStepOrderAreMeaningful() {
    var a = JsonParser.parseString("{\"steps\":[1,2]}").getAsJsonObject();
    var b = JsonParser.parseString("{\"steps\":[2,1]}").getAsJsonObject();
    assertNotEquals(TaskIdentity.signature("restock", a), TaskIdentity.signature("restock", b));
    assertNotEquals(TaskIdentity.signature("restock", a), TaskIdentity.signature("transport", a));
  }
}
