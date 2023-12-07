package de.hbvjena90;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;

public class Main {

    static final FileInputStream serviceAccount;

    static final FirebaseOptions options;

    static final AndroidConfig androidConfigHigh = AndroidConfig.builder()
            .setPriority(AndroidConfig.Priority.HIGH).setTtl(28L * 24 * 3600 * 1000).build();

    static final AndroidConfig androidConfigLow = AndroidConfig.builder()
            .setPriority(AndroidConfig.Priority.NORMAL).setTtl(28L * 24 * 3600 * 1000).build();

    static FirebaseApp app;

    static {
        try {
            serviceAccount = new FileInputStream("firebase/firebase-admin-token.json");
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    static {
        try {
            options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(serviceAccount))
                    .build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {
        boolean debugMode = args.length > 0;

        Scanner scanner = new Scanner(System.in);

        app = FirebaseApp.initializeApp(options);

        outerLoop:
        while (true) {
            try {
                JSONObject jsonObject;

                if (!debugMode) {
                    jsonObject = new JSONObject(scanner.nextLine());
                } else {
                    jsonObject = new JSONObject();
                    jsonObject.put("method", "tokens");
                    jsonObject.put("type", "test_silent");

                    System.out.println("Enter the token to test to:");
                    String token = scanner.nextLine();

                    System.out.println("Please input the type: (\"genericLow\", \"android\", \"ios\")");
                    String type = scanner.nextLine();

                    JSONObject data = new JSONObject();
                    long millis = System.currentTimeMillis();
                    data.put("sendTime", String.valueOf(millis));
                    int randomId = (int) (Math.random() * 1000000);
                    data.put("id", String.valueOf(randomId));
                    jsonObject.put("data", data);
                    JSONObject tokens = new JSONObject();


                    if (type.equalsIgnoreCase("genericLow")) {
                        tokens.put("genericLow", new JSONArray().put(token));
                        tokens.put("android", new JSONArray());
                        tokens.put("ios", new JSONArray());
                    } else if (type.equalsIgnoreCase("android")) {
                        tokens.put("genericLow", new JSONArray());
                        tokens.put("android", new JSONArray().put(token));
                        tokens.put("ios", new JSONArray());
                    } else if (type.equalsIgnoreCase("ios")) {
                        tokens.put("genericLow", new JSONArray());
                        tokens.put("android", new JSONArray());
                        tokens.put("ios", new JSONArray().put(token));
                    } else {
                        System.err.println("Invalid type!");
                        continue;
                    }
                    jsonObject.put("tokens", tokens);

                    System.out.println("Sending test_silent message to " + token + " with type " + type + " and id " + randomId + "...");
                }

                String method = jsonObject.getString("method");

                AndroidConfig scopedAndroidConfigHigh = androidConfigHigh;
                AndroidConfig scopedAndroidConfigLow = androidConfigLow;

                long unix = System.currentTimeMillis() / 1000L;
                unix += 28 * 24 * 3600L;

                // json might have TTL
                if (jsonObject.has("ttl")) {
                    long ttl = jsonObject.getLong("ttl");
                    scopedAndroidConfigHigh = AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH).setTtl(ttl * 1000L).build();
                    scopedAndroidConfigLow = AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.NORMAL).setTtl(ttl * 1000L).build();

                    unix = System.currentTimeMillis() / 1000L;
                    unix += ttl;
                }

                switch (method) {
                    case "exit":
                        break outerLoop;
                    case "tokens":
                        // jsonObject looks like:
                        // {
                        //   "method": "tokens",
                        //   "tokens": {
                        //     "android": ["token1", "token2", ...],
                        //     "ios": ["token1", "token2", ...]
                        //   },
                        //   "type": "type",
                        //   "data": {
                        //     "key1": "value1",
                        //     "key2": "value2",
                        //     ...
                        //   }
                        // }
                        JSONObject tokens = jsonObject.getJSONObject("tokens");

                        JSONArray androidTokensArray = tokens.getJSONArray("android");
                        JSONArray iosTokensArray = tokens.getJSONArray("ios");
                        JSONArray genericLowTokensArray = tokens.getJSONArray("genericLow");

                        HashSet<String> androidTokens = new HashSet<>();
                        HashSet<String> iosTokens = new HashSet<>();
                        HashSet<String> genericLowTokens = new HashSet<>();
                        for (int i = 0; i < androidTokensArray.length(); i++) {
                            androidTokens.add(androidTokensArray.getString(i));
                        }
                        for (int i = 0; i < iosTokensArray.length(); i++) {
                            iosTokens.add(iosTokensArray.getString(i));
                        }
                        for (int i = 0; i < genericLowTokensArray.length(); i++) {
                            genericLowTokens.add(genericLowTokensArray.getString(i));
                        }

                        String type = jsonObject.getString("type");
                        JSONObject data = jsonObject.getJSONObject("data");

                        Map<String, String> dataMap = new HashMap<>();
                        for (String key : data.keySet()) {
                            dataMap.put(key, data.getString(key));
                        }

                        // treat the Android tokens with silent push notifications
                        while (!androidTokens.isEmpty()) {
                            // Get the first 500 tokens
                            HashSet<String> tokenSubset = new HashSet<>();
                            Iterator<String> iterator = androidTokens.iterator();
                            for (int i = 0; i < 500 && iterator.hasNext(); i++) {
                                tokenSubset.add(iterator.next());
                            }

                            // Remove the first 500 tokens from the original set
                            androidTokens.removeAll(tokenSubset);

                            // Prepare the message
                            MulticastMessage message = MulticastMessage.builder()
                                    .putData("type", type)
                                    .putAllData(dataMap)
                                    .addAllTokens(tokenSubset)
                                    .setAndroidConfig(scopedAndroidConfigHigh)
                                    .build();

                            // Get the response
                            BatchResponse response = FirebaseMessaging.getInstance(app).sendMulticast(message);

                            if (response.getFailureCount() > 0) {
                                List<SendResponse> responses = response.getResponses();
                                for (int i = 0; i < responses.size(); i++) {
                                    if (!responses.get(i).isSuccessful()) {
                                        System.err.println("MALTOKEN " + tokenSubset.toArray()[i]);
                                    }
                                }
                            }
                            System.out.println("Sent " + (tokenSubset.size() - response.getFailureCount()) + " ANDROID message(s)" + (tokenSubset.size() - response.getFailureCount() == 1 ? "" : "s") + " to " + tokenSubset.size() + " token" + (tokenSubset.size() == 1 ? "" : "s"));
                        }

                        ApnsConfig iosConfigLow = ApnsConfig.builder()
                                .setAps(Aps.builder().setContentAvailable(true).build())
                                .putHeader("apns-priority", "5")
                                .putHeader("apns-expiration", String.valueOf(unix))
                                .build();

                        // treat the Android tokens with silent push notifications
                        while (!genericLowTokens.isEmpty()) {
                            // Get the first 500 tokens
                            HashSet<String> tokenSubset = new HashSet<>();
                            Iterator<String> iterator = genericLowTokens.iterator();
                            for (int i = 0; i < 500 && iterator.hasNext(); i++) {
                                tokenSubset.add(iterator.next());
                            }

                            // Remove the first 500 tokens from the original set
                            genericLowTokens.removeAll(tokenSubset);

                            // Prepare the message
                            MulticastMessage message = MulticastMessage.builder()
                                    .putData("type", type)
                                    .putAllData(dataMap)
                                    .addAllTokens(tokenSubset)
                                    .setAndroidConfig(scopedAndroidConfigLow)
                                    .setApnsConfig(iosConfigLow)
                                    .build();

                            // Get the response
                            BatchResponse response = FirebaseMessaging.getInstance(app).sendMulticast(message);

                            if (response.getFailureCount() > 0) {
                                List<SendResponse> responses = response.getResponses();
                                for (int i = 0; i < responses.size(); i++) {
                                    if (!responses.get(i).isSuccessful()) {
                                        System.err.println("MALTOKEN " + tokenSubset.toArray()[i]);
                                    }
                                }
                            }
                            System.out.println("Sent " + (tokenSubset.size() - response.getFailureCount()) + " GENERIC LOW message(s)" + (tokenSubset.size() - response.getFailureCount() == 1 ? "" : "s") + " to " + tokenSubset.size() + " token" + (tokenSubset.size() == 1 ? "" : "s"));
                        }

                        ApnsConfig iosConfigHigh = ApnsConfig.builder()
                                .setAps(
                                        Aps.builder()
                                                .setContentAvailable(false)
                                                .setMutableContent(true)
                                                .setSound("default")
                                                .build()
                                )
                                .putHeader("apns-priority", "10")
                                .putHeader("apns-push-type", "alert")
                                .putHeader("apns-expiration", String.valueOf(unix))
                                .build();

                        // treat the iOS tokens with alerting push notifications
                        while (!iosTokens.isEmpty()) {
                            // Get the first 500 tokens
                            HashSet<String> tokenSubset = new HashSet<>();
                            Iterator<String> iterator = iosTokens.iterator();
                            for (int i = 0; i < 500 && iterator.hasNext(); i++) {
                                tokenSubset.add(iterator.next());
                            }

                            // Remove the first 500 tokens from the original set
                            iosTokens.removeAll(tokenSubset);

                            // add the intent to test the data
                            JSONObject payload = new JSONObject();
                            payload.put("type", "openProfile");
                            payload.put("userId", "1");
                            JSONObject contentModelMap = new JSONObject();
                            contentModelMap.put("payload", payload);
                            JSONObject modelMap = new JSONObject();
                            modelMap.put("content", contentModelMap);
                            dataMap.put("data", modelMap.toString());

                            // Prepare the message
                            MulticastMessage message = MulticastMessage.builder()
                                    .putData("type", type)
                                    .putAllData(dataMap)
                                    .addAllTokens(tokenSubset)
                                    .setApnsConfig(iosConfigHigh)
                                    .setNotification(
                                            Notification.builder()
                                                    .setTitle("(V-S) Synchronisierung fehlgeschlagen")
                                                    .setBody("Du hast eine Benachrichtigung erhalten, aber die Synchronisierung ist fehlgeschlagen. Öffne die App, um die Synchronisierung zu starten.")
                                                    .build()
                                    )
                                    .build();

                            // Get the response
                            BatchResponse response = FirebaseMessaging.getInstance(app).sendMulticast(message);

                            if (response.getFailureCount() > 0) {
                                List<SendResponse> responses = response.getResponses();
                                for (int i = 0; i < responses.size(); i++) {
                                    if (!responses.get(i).isSuccessful()) {
                                        System.err.println("MALTOKEN " + tokenSubset.toArray()[i]);
                                    }
                                }
                            }
                            System.out.println("Sent " + (tokenSubset.size() - response.getFailureCount()) + " IOS message(s)" + (tokenSubset.size() - response.getFailureCount() == 1 ? "" : "s") + " to " + tokenSubset.size() + " token" + (tokenSubset.size() == 1 ? "" : "s"));
                        }
                        break;
                    case "topic":
                        String topic = jsonObject.getString("topic");
                        JSONObject mapData = jsonObject.getJSONObject("data");
                        mapData.put("type", jsonObject.getString("type"));

                        Map<String, String> dataMap2 = new HashMap<>();
                        for (String key : mapData.keySet()) {
                            dataMap2.put(key, mapData.getString(key));
                        }

                        Message message = Message.builder()
                                .setAndroidConfig(scopedAndroidConfigLow)
                                .setApnsConfig(ApnsConfig.builder()
                                        .setAps(Aps.builder().setContentAvailable(true).build())
                                        .putHeader("apns-priority", "5")
                                        .putHeader("apns-expiration", String.valueOf(unix))
                                        .build())
                                .putAllData(dataMap2)
                                .setTopic(topic)
                                .build();

                        try {
                            FirebaseMessaging.getInstance(app).send(message);
                        } catch (FirebaseMessagingException e) {
                            System.err.println("MALTOPIC " + topic);
                        }
                        break;
                }
            } catch (Exception ignored) {
            }
        }
    }
}