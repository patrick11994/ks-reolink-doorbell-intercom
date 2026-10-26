# Reolink Doorbell Intercom

A [Kiosk Satellite](https://kiosksatellite.com) plugin that turns a wall panel into an intercom for a Reolink video doorbell. When someone presses the button, the panel rings at once and shows the doorbell's live video. You can listen in, and you can talk to the door.

The plugin talks to the doorbell directly on your own network. It needs no Home Assistant, go2rtc, Scrypted, cloud account or other server in between.

<p align="center">
  <img src="docs/screenshot.jpg" alt="The call screen on a wall panel: the doorbell's live video, with Microphone, Speaker and End call below it" width="320">
</p>

*Not affiliated with or endorsed by Reolink or the Kiosk Satellite project. Reolink is a trademark of its owner.*

## Features

- **Rings at once.** The plugin keeps a connection open to the doorbell, so a press reaches the panel without delay. It wakes the screen, closes the screensaver, plays a soft chime and opens a full-screen call screen. Only the button rings: motion and person detection never do.
- **Live video.** H.264 straight from the doorbell, decoded in hardware. The first picture shows about 0.5 to 1.5 s after the press.
- **Listen, then answer.**
  - **Speaker** plays the sound at the door, with a volume slider on screen, since most panels have no volume buttons.
  - **Answer** opens the microphone so you can talk to the door.
  - During a call, **Microphone** and **Speaker** turn each side on and off, and **End call** hangs up.
- **Closes by itself** when nobody answers. A ring around **Hang up** shows the time left. A touch starts the time over, and Speaker or Answer stop it.
- **Open it any time** to look outside. Add the **Open** action to the kiosk menu, a gesture or Home Assistant. Opened by hand, it stays open until you close it.
- **Check button** on the settings page. It tests the address, the login, video and talking, and says what to fix.
- **English and Dutch** for the call screen and messages.

## Requirements

| | |
|---|---|
| **Doorbell** | A Reolink video doorbell on your local network, reachable from the panel. Tested with the **Reolink Video Doorbell PoE**, firmware v3.0.0.6460. Other Reolink doorbells that offer RTSP and ONVIF should work, but have not been tested. |
| **On the doorbell** | **RTSP** and **ONVIF** turned on. In the Reolink app, go to Settings > Network > Advanced > Server Settings. Talking uses the ONVIF audio backchannel. The doorbell must be a *local device*, not an *account device*. |
| **Panel** | Kiosk Satellite **2026.10.38** or newer, Android 8.0 or newer, with a microphone and a speaker. Tested on an RK3576 Android 14 panel. |

A doorbell that is connected through a Reolink NVR or Home Hub, rather than directly to your network, is not supported yet.

## Installation

### Community Plugins (Recommended)

Reolink Doorbell Intercom is listed in [Community Plugins](https://kiosksatellite.com/plugins/). Use the button below to open it in your Kiosk Satellite and install it.

[![Install in Kiosk Satellite](https://kiosksatellite.com/img/badges/plugin-add.svg)](https://kiosksatellite.com/redirect/plugin/?repository=patrick11994/ks-reolink-doorbell-intercom)

Or search for `Reolink Doorbell Intercom` in **Settings > Plugin Manager > Browse plugins** in Kiosk Satellite.

### Manual

1. Open **Settings > Plugin Manager** in Kiosk Satellite and turn on **Enable Plugins**.
2. Choose **Add plugin**, paste `https://github.com/patrick11994/ks-reolink-doorbell-intercom` and choose **Preview**.
3. Review the plugin and choose **Trust and install**.

## Setup

1. In the Reolink app, create a **separate user** for the panel. A normal (non-admin) user is enough for button presses, video and talking.
2. Enable the plugin, and fill in **Doorbell address**, **Username** and **Password**.
3. Press **Check**. It should say that button presses, video and talking all work. If not, it tells you what to change.
4. Optional: under **Actions**, open **Open** and turn on **Show in kiosk menu**. You can then look outside from the menu.
5. Optional: under **Gestures**, use the **Doorbell pressed** trigger to run something on every ring, such as showing a Home Assistant view.

## Settings

| Group | Setting | What it does | Default |
|---|---|---|---|
| Doorbell | **Doorbell address** | IP address or hostname of the doorbell | |
| Doorbell | **Username** / **Password** | A Reolink user on the doorbell. The password is stored as a masked secret. | |
| Doorbell | **Name** | Shown at the top of the call screen. Empty shows *Front door*. | empty |
| Doorbell | **Check the doorbell** | Tests button presses, video and talking | |
| Ringing | **Play a chime** | A soft ding-dong at the notification volume | on |
| Ringing | **Wake the screen** | Turns the screen on and closes the screensaver on a ring | on |
| Ringing | **Close the viewer after** | 30 seconds to 5 minutes, or Off. Counts only while it rings untouched. | 1 minute |
| Ringing | **End a call after** | 5, 10 or 30 minutes, or Off. Counts from Answer, Speaker or opening it by hand, so a forgotten call does not keep the room on the line. | 10 minutes |
| Video and audio | **Video** | *Main* (2560×1920, sharp) or *Sub* (640×480, light) | Main |
| Video and audio | **Call volume** | How loud the doorbell plays on the panel. The slider on the call screen changes it too. | 70 |
| Video and audio | **Microphone** | Automatic, or a fixed Android microphone source: Camcorder, Voice communication, Microphone, Voice recognition or Unprocessed | Automatic |
| Video and audio | **Microphone sample rate** | Automatic (16000 Hz, then 48000 Hz, then 8000 Hz), or one fixed rate | Automatic |
| Video and audio | **Android call mode** | Switches Android to call mode on the loudspeaker during a call, which most devices need for echo cancelling | on |
| Video and audio | **Language** | Automatic (the panel's Android language), English or Nederlands | Automatic |

**Actions:**

| Action | What it does |
|---|---|
| **Open** | Opens the call screen without a ring |
| **Test ring** | Does what a real press does |
| **Close** | Closes the call screen |

**Trigger:** **Doorbell pressed**, for Gestures.

## Good to know

- **Talking has a delay of about 2 seconds.** The doorbell itself holds about 2.1 s of incoming audio before it plays it. The Reolink app and HomeKit have the same delay, and this plugin adds less than 0.1 s to it. A red *Speaking* label reminds you while the microphone is on. In practice the conversation simply takes turns.
- **Echo.** The doorbell's sound plays as call audio, in Android's call mode on the loudspeaker, and Android's echo canceller is attached to the microphone where the device has one. That keeps the panel's own speaker out of the microphone on most devices. Echo cancelling usually works best with **Microphone** set to *Voice communication*. If a visitor hears themselves, try that, turn the panel's speaker down, or turn off **Speaker** while you talk.
- **The wake word** pauses during a call. While the doorbell's sound plays or the microphone is on, the plugin mutes Kiosk Satellite's wake word, so the door cannot trigger the assistant, and then unmutes it afterwards. A wake word you muted yourself stays muted.
- **Which microphone.** Panels differ in which Android microphone source really works; on an RK3576 panel only *Camcorder* does. **Automatic** uses the first source that opens, Camcorder first, and keeps it for the whole call. If the door does not hear you, pick another one under **Microphone**. If the door hears loud noise instead of you, try another **Microphone sample rate**: on an Echo Show, 48000 Hz gives noise and 16000 Hz is clean.

## Privacy and security

- **Nothing leaves your network.** The plugin connects only to the doorbell, on two ports: 9000 (button presses) and 554 (RTSP video and sound, and the ONVIF audio backchannel for talking). It has no analytics, and it contacts no other service.
- **The doorbell password** is stored as a Kiosk Satellite secret setting. Plugin Manager and Remote Admin mask it. Use a separate, non-admin Reolink user for the panel.
- **The connections are not encrypted.** Reolink's local protocols are not, so keep the doorbell and the panel on a trusted network.
- **The microphone** is only on while the call screen shows **Microphone** as on, and every call ends after **End a call after**. Nothing is recorded or stored.
- **What the doorbell sends is size-limited.** The plugin runs inside Kiosk Satellite, so it caps every answer, header line, video frame and push, and refuses XML with a DOCTYPE. A broken or hostile device on the network cannot take the app down.
- **The plugin runs inside Kiosk Satellite** with its permissions, like every plugin. Review the code before you trust it.

## Troubleshooting

| What you see | What to check |
|---|---|
| **Check** says it cannot reach the doorbell | The address, and that the panel and the doorbell are on the same network |
| **Check** says the username or password is refused | Try the same user in the Reolink app |
| **Check** says video does not work | Turn on RTSP in the Reolink app's Server Settings |
| **Check** says there is no talk channel | Turn on ONVIF in the Reolink app's Server Settings |
| The call screen stays on *Connecting to the doorbell…* | Run **Check**. Then look at Kiosk Satellite's **Logs** (in the settings or in Remote Admin). The plugin writes what failed there. |
| The door does not hear you | Try another source under **Microphone**, and check that Kiosk Satellite has the microphone permission |
| The door hears noise instead of your voice | Try another **Microphone sample rate**. The Logs show which source and rate a call used (*microphone: live, …*). |
| The door hears itself (echo or a feedback loop) | Set **Microphone** to *Voice communication*, and keep **Android call mode** on |
| Sound or the microphone stops working in a call | Turn off **Android call mode** |
| You hear nothing with Speaker on | Raise the slider on the call screen. It sets Android's call volume, which is separate from the media volume. |

## Building

You need JDK 17 or newer, Python 3 and an Android SDK with a platform and build-tools. Set `JAVA_HOME` and `ANDROID_HOME`.

```sh
python3 tools/test.py     # unit tests, with a fake doorbell
python3 tools/build.py    # dist/reolink-doorbell-<version>.zip
```

To test a local build, install the ZIP through **Plugin Manager > Developer Tools > Install from ZIP**. Releases are built by the GitHub Actions workflow when you publish a release, with the tag as the version.

## Credits

- Plugin SDK, template and build tools: [Kiosk Satellite plugin template](https://github.com/jxlarrea/kiosk-satellite-plugins) (Apache-2.0)
- Reolink's Baichuan protocol, for the button presses, was learned from [reolink_aio](https://github.com/starkillerOG/reolink_aio) (MIT). The plugin reimplements it and copies no code.
- Talking over the ONVIF audio backchannel follows the approach of [Scrypted](https://github.com/koush/scrypted)'s Reolink and ONVIF plugins. No code was copied.
- The interface follows ideas from [UniFi Protect Doorbell](https://github.com/mark-andrew/kiosk-satellite-unifi-doorbell) and [Kiosk Phone](https://github.com/mpjanofsky/kiosk-phone).

Built with a lot of help from Claude.

## License

[Apache-2.0](LICENSE). See [NOTICE](NOTICE).
