package com.exittrading.app.service.util;

import com.exittrading.app.domain.UserAccount;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility for parsing UserAccount holdings strings.
 * Format: "EXCHANGE|SYMBOL|ISIN|QTY|AUTH_QTY|PRICE|VALUE|TOKEN|..."
 */
public class UserAccountUtil {

    public static Set<Long> extractHoldingTokens(UserAccount user) {
        if (user == null || user.getHoldings() == null) return Collections.emptySet();
        return user.getHoldings().stream()
                .map(UserAccountUtil::parseToken)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    public static Long parseToken(String holdingLine) {
        if (holdingLine == null) return null;
        String[] parts = holdingLine.split("\\|");
        // Index 7 is usually Instrument Token
        if (parts.length >= 8 && parts[7] != null && !parts[7].isBlank()) {
            try {
                return Long.valueOf(parts[7].trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    public static String parseSymbol(String holdingLine) {
        if (holdingLine == null) return null;
        String[] parts = holdingLine.split("\\|");
        
        // Handle "EXCHANGE:SYMBOL" format in first part (e.g. NSE:INFY|...)
        if (parts.length > 0 && parts[0].contains(":")) {
             String[] split = parts[0].split(":");
             if (split.length > 1) return split[1].trim();
        }

        if (parts.length >= 2 && parts[1] != null) {
            return parts[1].trim();
        }
        return null;
    }
}
