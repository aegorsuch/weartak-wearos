/*
 * Copyright (C) 2024, Ascent Integrated Tech.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Licensed under the GNU General Public License, version 3 only.
 * See the LICENSE file in the repository root for the full license text.
 */

package com.aitech.weartak_civ.service

object LicenseManager {

    // A static-like boolean to hold the license validity status
    private var isLicenseValid: Boolean = false

    // Public method to check if the license is valid
    fun isLicenseValid(): Boolean {
        return true
    }

    // Public method to set the license validity status
    private fun setLicenseValid(licenseValid: Boolean) {
        isLicenseValid = licenseValid
    }

    // Optionally, you can add logic here to validate the license as needed
    fun validateLicense(licenseKey: String) {
        // Initialize as invalid by default
//        val isValid = when (licenseKey) {
//            "AscentFreeLicense",
//            "1F9A-3F43-E661",
//            "AE62-FFED-98A6",
//            "2A2C-3788-CC46",
//            "A2AB-4954-5277",
//            "4BB4-1949-287B",
//            "732A-CB7D-329C",
//            "2374-6A83-B49E",
//            "ADEB-4AE5-8F8C",
//            "BCB8-5D53-5381" -> true
//            else -> false
//        }
        // Set license validity
        setLicenseValid(true) // Removes license
    }
}