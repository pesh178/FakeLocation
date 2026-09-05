# Case Scope

## meta
- case_id: fakedev-lsposed
- created: 2026-09-04T11:13:18.6671523+08:00
- operator: local
- project_root: C:\FakeLocation
- primary_skill: apk-reverse/SKILL.md
- primary_id: R1
- lead_role: lead
- specialist_roles: []
- hint: 为本地 fakedev Android 模拟器安装完整 LSPosed libxposed 支持并测试自有 FakeLocation APK
- preset: own-system

## auth
- status: granted
- basis: own_system
- evidence_of_auth: preset:own-system/lab
- MUST NOT proceed if status != granted

## in_scope
- assets:
  - emulator-5554
  - C:\Android\lsposed\LSPosed-v1.9.2-zygisk.zip
  - C:\Android\magisk.apk
  - C:\FakeLocation\app\build\outputs\apk\debug\FakeLocation_v1.6.2.apk
- surfaces: []
- activities: []

## out_of_scope
- assets: []
- activities: [dos, phishing_real_users, unrestricted_exfil]

## network_profile
- mode: lab_only
- notes: |
    offline | lab_only | authorized_target_only | unrestricted_lab
    Change mode only after auth.status = granted.

## deliverables
- report: true
- field_journal: true
- diagrams: true
- timeline: true

## constraints
- timebox: {}
- stealth: low
- data_handling: anonymize

## signoff
- ready_for_act: true
- checklist:
  - [x] auth.status = granted
  - [x] in_scope.assets non-empty OR offline sample path set
  - [x] network_profile.mode chosen
  - [ ] out_of_scope reviewed
  - [ ] roles assigned (see skills/ops/role-map.md)

## ops_refs
- skills/ops/scope-contract.md
- skills/ops/evidence-finding-path.md
- skills/ops/role-map.md
- skills/ops/timeline-workitem.md
- skills/ops/IDENTITY.md