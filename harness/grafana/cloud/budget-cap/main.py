"""Hard cap for the public video bucket (2026-09-27, the maintainer's request): a Cloud Run function on the Pub/Sub topic
pzopt-budget-cap, where the budget "pzopt videos hard cap" (EUR 10 a month, costs labelled app=pzopt-videos: the
bucket gs://diegov-videos-coldline) posts its notifications. When the reported cost reaches the budget it disables
billing on project diegov (Google's documented hard stop): everything in the project stops, the public dashboard
included, until billing is linked again by hand. A message with "dryRun": true logs what it would do and does nothing.
Budget costs arrive with a delay of hours: the cap is not instantaneous."""
import base64
import json
import os

import functions_framework
from googleapiclient import discovery

PROJECT = os.environ.get("PROJECT", "diegov")
BUDGET = os.environ.get("BUDGET_NAME", "pzopt videos hard cap")


@functions_framework.cloud_event
def cap(event):
    data = json.loads(base64.b64decode(event.data["message"]["data"]).decode())
    cost, budget = float(data.get("costAmount", 0)), float(data.get("budgetAmount", 0))
    dry = data.get("dryRun") is True
    print(json.dumps({"budget_name": data.get("budgetDisplayName"), "cost": cost, "budget": budget, "currency": data.get("currencyCode"), "dry_run": dry}))
    if data.get("budgetDisplayName") != BUDGET:
        print("not our budget: ignored")
        return
    if budget <= 0 or cost < budget:
        return
    billing = discovery.build("cloudbilling", "v1", cache_discovery=False)
    name = f"projects/{PROJECT}"
    info = billing.projects().getBillingInfo(name=name).execute()
    if not info.get("billingEnabled"):
        print("billing already disabled on", name)
        return
    if dry:
        print("DRY RUN: would disable billing on", name, "(linked to", info.get("billingAccountName"), ")")
        return
    billing.projects().updateBillingInfo(name=name, body={"billingAccountName": ""}).execute()
    print("CAP REACHED: billing disabled on", name)
