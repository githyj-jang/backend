#!/bin/bash
# Terraform State Lock 강제 해제 스크립트

SESSION_ID="user-02-penguin"
LOCK_ID="198e8aeb-21d9-5621-bc64-e70cecc4dffb"

echo "🔓 Forcing unlock for session: $SESSION_ID"
echo "Lock ID: $LOCK_ID"

cd terraform-workspaces/$SESSION_ID

# Workspace 선택
terraform workspace select $SESSION_ID

# 강제 unlock
terraform force-unlock -force $LOCK_ID

echo "✅ Lock released!"
echo "Now you can run destroy again."

