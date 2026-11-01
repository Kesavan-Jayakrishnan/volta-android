# Your Google Maps Platform key

Volta plans trips with Google's Routes and Places APIs. It has no key of its own: you create one in your Google
account and paste it into the app. This takes about ten minutes the first time.

## 1. Create a project

1. Open [console.cloud.google.com](https://console.cloud.google.com) and sign in.
2. Top bar → project picker → **New project** → name it `volta` → **Create**. Make sure it's selected.

## 2. Turn on billing

Menu → **Billing** → link a billing account. Google Maps Platform needs one even when you stay within its monthly
free usage, which normally covers one person's trip planning. Check the current free amounts on
[Google's pricing page](https://mapsplatform.google.com/pricing/).

## 3. Enable the APIs

APIs & Services → **Library**, then search for each and press **Enable**:

- **Routes API**
- **Places API (New)**

## 4. Create the key

APIs & Services → **Credentials** → **Create credentials** → **API key**. Copy it; it starts with `AIza`.

## 5. Restrict the key

Open the key you just made (**Edit API key**):

- **API restrictions** → *Restrict key* → tick only **Routes API** and **Places API (New)**.
- **Application restrictions** → *Android apps* → **Add**:
  - Package name: `dev.irshadm.volta.oss`
  - SHA-1 certificate fingerprint: the one for the APK you installed. For a release from this repository it's
    listed on the Releases page. For your own build, run `./gradlew signingReport` and use the *debug* SHA-1.

If you get the SHA-1 wrong, Volta's key check says *"The key is restricted to other Android apps"* and shows the
package and SHA-1 it sent, so you can copy them exactly.

## 6. Cap the cost

A **budget alert** emails you; it does not stop spending. A **quota** does.

1. Billing → **Budgets & alerts** → **Create budget**: scope the `volta` project, a small monthly amount, alerts at
   50 %, 90 % and 100 %.
2. APIs & Services → each enabled API → **Quotas** → lower *requests per day* to what you'd actually use, for
   example 200. This is what protects you if the key ever leaks.

## 7. Add it to Volta

Paste the key on the *Add your Google Maps key* screen (or Home → Google Maps key) → **Check key**.
Volta saves it only when both APIs answer.

## If the key leaks

Credentials → your key → **Regenerate key**, then paste the new one into Volta.

## Troubleshooting

| Volta says | Fix |
|---|---|
| Google doesn't recognise this key | Copy it again from Credentials. |
| *API* isn't enabled for this key's project | Step 3. It can take a few minutes after enabling. |
| This key's API restrictions leave out *API* | Step 5, API restrictions. |
| The key is restricted to other Android apps | Step 5, Application restrictions: package and SHA-1. |
| The project has no billing account | Step 2. |
| This key has used up its quota | Wait, or raise the quota in step 6. |
