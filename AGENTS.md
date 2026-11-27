# GitHub workflow

- Across all chats in this project, when pushing changes to GitHub, automatically push a branch, open a pull request, and merge it into `main` without asking for confirmation (never push directly to `main`).

# Contacts tab parity

- The Contacts tab in Sahl Phone (`Data.people`, `MainActivity.contactsView`, `PeopleAdapter`, `contactActions`, `openContact`) must keep the same criteria, grouping, filtering, and behaviors as Sahl Contacts (`Repo.list`, `MainActivity`). When updating either app's contacts list or actions, mirror the update in the other repository.

# Planning before editing

- For any task that modifies code or files (unless I say "skip plan"):
  - Inspect the relevant files first without editing anything.
  - Output a concise plan (goal, exact files to change, risks, and test steps).
  - End with "Plan ready. Reply 'Proceed' to execute." and STOP.
  - Only edit files and run tests after I reply "Proceed".
