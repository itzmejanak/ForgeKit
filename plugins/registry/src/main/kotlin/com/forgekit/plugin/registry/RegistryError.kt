package com.forgekit.plugin.registry

import com.forgekit.core.model.ForgeError

/**
 * Concrete typed error for the registry domain (ARCHITECTURE §74:
 * domain modules define their own ForgeError subclass).
 * Transport failures, unknown ids, hash mismatches and malformed
 * index documents all fail with this precise type.
 */
public class RegistryError(
    message: String,
    detail: String? = null,
) : ForgeError(message, detail)
