// SPDX-License-Identifier: Apache-2.0
package io.github.patrick11994.reolinkdoorbell;

import java.util.Locale;

/**
 * Everything the plugin shows by itself: the viewer, its readings, status and the connection check. The settings
 * page comes from the manifest, which Kiosk Satellite shows as written (English). Add a language by adding a column.
 */
final class Texts {
    private final boolean dutch;

    private Texts(boolean dutch) {
        this.dutch = dutch;
    }

    /** choice is the Language setting: "Automatic" follows the panel's Android language. */
    static Texts of(String choice) {
        if ("Nederlands".equals(choice)) return new Texts(true);
        if ("English".equals(choice)) return new Texts(false);
        return new Texts("nl".equals(Locale.getDefault().getLanguage()));
    }

    private String t(String english, String dutch) {
        return this.dutch ? dutch : english;
    }

    // Viewer
    String answer() { return t("Answer", "Opnemen"); }
    String speaker() { return t("Speaker", "Luidspreker"); }
    String microphone() { return t("Microphone", "Microfoon"); }
    String hangUp() { return t("Hang up", "Ophangen"); }
    String endCall() { return t("End call", "Beëindigen"); }
    String on() { return t("on", "aan"); }
    String off() { return t("off", "uit"); }
    String microphoneActive() { return t("Microphone active", "Microfoon actief"); }
    String connecting() { return t("Connecting to the doorbell…", "Verbinden met de deurbel…"); }
    String noPictureYet() { return t("No picture from the doorbell yet", "Nog geen beeld van de deurbel"); }
    String noVideo(String reason) { return t("No video: ", "Geen beeld: ") + reason; }
    String noMicrophone() { return t("No working microphone found", "Geen werkende microfoon gevonden"); }
    String defaultName() { return t("Front door", "Voordeur"); }

    // Readings and status
    String connectionReading() { return t("Doorbell connection", "Verbinding met deurbel"); }
    String lastRingReading() { return t("Last ring", "Laatst aangebeld"); }
    String firstPictureReading() { return t("First picture", "Eerste beeld"); }
    String connected() { return t("connected", "verbonden"); }
    String connectingState() { return t("connecting", "verbinden"); }
    String notConfigured() { return t("not configured", "niet ingesteld"); }
    String noneYet() { return t("none yet", "nog niet"); }
    String waiting() { return t("waiting", "wachten"); }
    String afterRing(long millis) { return millis + t(" ms after the ring", " ms na het aanbellen"); }
    String enterSettings() { return t("Enter the doorbell's address, username and password", "Vul het adres, de gebruikersnaam en het wachtwoord van de deurbel in"); }
    String connectedTo(String name) { return t("Connected to ", "Verbonden met ") + name; }
    String doorbellProblem(String detail) { return t("Doorbell: ", "Deurbel: ") + detail; }

    // Connection check
    String checking() { return t("Checking the doorbell…", "Deurbel controleren…"); }
    String checkOk() { return t("Everything works: button presses, video and talking.", "Alles werkt: aanbellen, beeld en spreken."); }
    String checkNotConfigured() { return t("Fill in the address, username and password first.", "Vul eerst het adres, de gebruikersnaam en het wachtwoord in."); }
    String checkUnreachable(String host) {
        return t("Cannot reach " + host + ". Check the address, and that the doorbell is on the same network.",
            host + " is niet bereikbaar. Controleer het adres, en of de deurbel in hetzelfde netwerk zit.");
    }
    String checkLoginRefused() { return t("The doorbell refused the username or password.", "De deurbel weigert de gebruikersnaam of het wachtwoord."); }
    String checkAccountDevice() {
        return t("The doorbell is set up as a Reolink 'account device', which blocks local access. Switch it back to a local device in the Reolink app.",
            "De deurbel staat ingesteld als Reolink-'accountapparaat', dat lokale toegang blokkeert. Zet hem in de Reolink-app terug naar een lokaal apparaat.");
    }
    String checkNoVideo() {
        return t("Button presses work, but video does not: turn on RTSP in the Reolink app (Settings > Network > Advanced > Server Settings).",
            "Aanbellen werkt, maar beeld niet: zet RTSP aan in de Reolink-app (Instellingen > Netwerk > Geavanceerd > Serverinstellingen).");
    }
    String checkNoTalk() {
        return t("Button presses and video work, but the doorbell offers no talk channel: turn on ONVIF in the Reolink app (Settings > Network > Advanced > Server Settings).",
            "Aanbellen en beeld werken, maar de deurbel biedt geen spreekkanaal: zet ONVIF aan in de Reolink-app (Instellingen > Netwerk > Geavanceerd > Serverinstellingen).");
    }
    String checkFailed(String detail) { return t("The check failed: ", "De controle is mislukt: ") + detail; }
}
