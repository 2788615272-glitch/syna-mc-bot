package com.syna.bridge;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

final class SynaIdentityLoreDirector {
    private SynaIdentityLoreDirector() {}

    static JsonObject record(MinecraftServer server, String topicText, int version) {
        if (server == null) return receipt(false, "identity_lore_no_server");
        IdentityLorePolicy.Topic topic = IdentityLorePolicy.parseTopic(topicText);
        if (topic == null) return receipt(false, "identity_lore_unknown_topic");

        SynaStoryData data = SynaStoryData.get(server);
        int allowed = IdentityLorePolicy.allowedVersion(topic, data.chapter, data.clues);
        if (version <= 0 || version > allowed) {
            return receipt(false, "identity_lore_version_not_unlocked");
        }

        String disclosure = topic.id + ":v" + version;
        boolean added = data.identityDisclosures.add(disclosure);
        data.lastReason = added ? "identity_lore_recorded:" + disclosure : "identity_lore_repeated:" + disclosure;
        data.setDirty();
        BridgeState.get().setLastEvent(data.lastReason);
        JsonObject receipt = receipt(true, added ? "identity_lore_recorded" : "identity_lore_already_recorded");
        receipt.addProperty("topic", topic.id);
        receipt.addProperty("version", version);
        return receipt;
    }

    static JsonObject state(SynaStoryData data) {
        JsonObject json = new JsonObject();
        for (IdentityLorePolicy.Topic topic : IdentityLorePolicy.Topic.values()) {
            json.addProperty(topic.id + "Version",
                    IdentityLorePolicy.allowedVersion(topic, data.chapter, data.clues));
        }
        return json;
    }

    private static JsonObject receipt(boolean accepted, String result) {
        JsonObject receipt = new JsonObject();
        receipt.addProperty("accepted", accepted);
        receipt.addProperty("completed", accepted);
        receipt.addProperty("result", result);
        return receipt;
    }
}
