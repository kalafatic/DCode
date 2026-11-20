# DCode — Launch Guide

| Launch target | How | Notes |
|---|---|---|
| **Development** | Eclipse → `.launches/DCode_RCP_Application.launch` | PDE Runtime Workbench |
| **Debug** | Eclipse Debug Configurations (duplicate of RCP Application) | |
| **Headless server** | Eclipse `.launches/DCode_Headless_Server.launch` or `scripts/run-headless.sh` | |
| **Tests** | Eclipse `.launches/DCode_Tests.launch` | PDE JUnit |
| **Product (GUI, built)** | `product/eu.kalafatic.dcode.product/target/products/eu.kalafatic.dcode.ui.rcp/linux/gtk/x86_64/dcode` | needs display |
| **Product (headless, built)** | `product/eu.kalafatic.dcode.product/target/products/eu.kalafatic.dcode.server.headless/linux/gtk/x86_64/dcode-headless` | headless-capable |

UPM detects both built products (`Product: dcode`, `Product: dcode-headless`). GUI launches were not verified headlessly; both product launchers verified to exist and be executable after build.
