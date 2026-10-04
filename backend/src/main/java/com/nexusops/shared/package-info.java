/**
 * Shared kernel: cross-cutting infrastructure (request context, errors, security baseline,
 * database guards). OPEN so every business module may depend on it; it must never depend on them.
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.nexusops.shared;
