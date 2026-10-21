# Push offer countdown: web today, the app tomorrow

Built 2026-10-22 (V44). The owner asked for "timer of offer running out, like Myntra".

## One campaign for web and app

A countdown is a property of the **campaign** (`ends_at`), not of a device. Every push already goes to every live device of a person as a **data-only FCM message**: web browsers (`WEB`), and later the Flutter app (`ANDROID`, `IOS_APP`). Each device draws the notification itself from the same data, so web and app need **no separate campaigns**.

Data every countdown push carries (all strings, FCM data):

| Field | Meaning | Web service worker | Flutter app |
|---|---|---|---|
| `title`, `body` | `{time_left}` already filled, e.g. "Dhanteras offer ends in 5h 12m" | shows them | shows them |
| `ends_at` | the offer's end, epoch **milliseconds** | `renotify: true`: a reminder replaces the last notification and alerts again | **live ticking timer** (below) |
| `image` | the countdown poster, drawn when fetched | large image | big picture |
| `tag` | `campaign:<id>`, the same for the campaign and its reminders | replaces | use as notification id, so reminders replace |
| `url` | click-through with UTMs | opens it | deep link |
| `sid`, `sig` | send id and its signature | click beacon | send the same click beacon (`/apps/push/click`) |

## What each surface can do

- **Web (Chrome on Android and desktop):** a notification cannot tick. So:
  1. The text says the time left as of the send.
  2. The poster carries an "OFFER ENDS IN HH:MM:SS" band, drawn by ingest-api (`/media/countdown/<campaign id>`) when the phone fetches it, which is when the notification appears.
  3. "Last chance" pushes go at 24 h, 3 h and 1 h before the end (`push_offer_countdown_v1`) to people who have not tapped. Each replaces the previous notification.
  4. Nothing is delivered after the end: the TTL is capped to the time left, and the router refuses with `offer_ended`.
- **Android app:** a real countdown in the notification. Android's chronometer counts down to `ends_at`, the way Myntra and Flipkart do. With `flutter_local_notifications`:

  ```dart
  FirebaseMessaging.onBackgroundMessage(handler);   // data-only: the app draws it
  // in the handler / onMessage:
  final endsAt = int.tryParse(data['ends_at'] ?? '');
  await plugin.show(data['tag'].hashCode, data['title'], data['body'], NotificationDetails(
    android: AndroidNotificationDetails('offers', 'Offers',
      importance: Importance.high,
      when: endsAt,                        // the end
      usesChronometer: endsAt != null,     // ticking
      chronometerCountDown: true,          // counting down to `when`
      timeoutAfter: endsAt == null ? null : endsAt - DateTime.now().millisecondsSinceEpoch,  // gone at the end
      styleInformation: data['image'] == null ? null : BigPictureStyleInformation(/* download data['image'] */),
    )), payload: data['url']);
  ```

- **iPhone app:** notifications cannot tick on iOS. A **Live Activity** (iOS 16.1+, `Text(timerInterval:)`, Flutter plugin `live_activities`) can, on the lock screen. That is a later step; until then the iOS app shows the text and poster like the web.

**Still to build for the app:** a registration endpoint for app tokens, recording platform `ANDROID`/`IOS_APP` and the in-app consent copy. The storefront's `/apps/push/register` is web-only.

## Operating it

In the console composer, for a **push** campaign, open "Offer timer":
- the end (IST);
- an offer name (at most 24 characters; it goes in the reminder title);
- `{time_left}` in your own title or text;
- the poster band and the reminders.

The reminders replace the WhatsApp follow-up, so a campaign has one or the other.

**Mind the policy:**
- **Quiet hours 21:00–09:00 IST:** a push due then is held until 09:00, and one held past the end is never sent. **End offers at 21:00**, so the 1-hour reminder goes at 20:00 and the 3-hour one at 18:00. A 24-hour reminder due at 21:00 the day before goes at 09:00 instead.
- **Push marketing cap: 3 per person per day.** The campaign push plus three reminders across two days fits.
