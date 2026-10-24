# Clinic Reminders

WhatsApp reminders and bookings for private clinics in Lebanon.

Missed appointments cost clinics money, and secretaries spend hours answering WhatsApp. Clinic
Reminders handles both:
- it reminds patients automatically
- it lets them book and cancel on WhatsApp by themselves
- it gives the clinic a simple dashboard to run the day

It's sold as a done-for-you service: one deployment serves many clinics, each with its own WhatsApp
number.

## What it does

**For patients, on WhatsApp, with nothing to install**
- A reminder the day before and 2 hours before, with **Confirm** and **Cancel** buttons.
- Booking in a few taps: doctor, day, time, and who it's for. A parent can book for their children
  from one phone.
- **My appointment** to see or cancel, and **Info** for the address and opening hours.
- An "Add to my calendar" link after booking. Replying **STOP** ends all messages.

**For the clinic, on a web dashboard in Arabic or English**
- **Today** and **Week** views per doctor: book, move by drag and drop, cancel, mark arrived or no-show.
- **Patients** search, with deletion on request.
- A **Needs attention** bell for patients waiting for a call and messages that failed.
- **Doctors' hours and days off.** Closing a day that has bookings offers to message those patients.
- A **monthly report**: slots saved by early cancellations, no-shows, confirmations, busy hours and
  message costs, with a CSV export.
- Optional logins for doctors, who see only their own appointments.

**For the operator, who runs the service**
- Add a clinic, connect its WhatsApp number, create its logins, and send a test message.
- See every clinic's month side by side, and keep Meta's message rates for cost estimates.

## Run it locally

You need JDK 21+ and Docker.

```bash
docker compose up -d db
./mvnw spring-boot:run "-Dspring-boot.run.profiles=dev"
```

Open http://localhost:8080. The dev profile uses a fake WhatsApp client, so nothing is sent.

| Username | Password | Sees |
| --- | --- | --- |
| `owner` | `dev` | The clinic |
| `secretary` | `dev` | The clinic |
| `doctor` | `dev` | One doctor's own appointments |
| `operator` | `dev` | All clinics (`/operator`) |

To reply as a patient, use the simulator at http://localhost:8080/dev/whatsapp.

For a sales demo clinic, add `"-Dspring-boot.run.arguments=--seed-demo"` to the run command. It
creates "Dr. Demo" with a few weeks of appointments and prints its login.

## Tests

```bash
./mvnw verify
```

Docker must be running for this one. Without Docker, `./mvnw test` runs the unit tests only.

## Configuration

Everything comes from environment variables; see [.env.example](.env.example).

## More

- [docs/SPEC.md](docs/SPEC.md): what the product does, how WhatsApp is used, and the milestones.
- [CLAUDE.md](CLAUDE.md): commands and the rules the code follows (multi-tenancy, privacy, time zones).

Not deployed yet: the server setup (M7) is the next milestone.
