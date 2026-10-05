package dev.chirana.umbrellaz.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

final class AuditInteractionSupport {
    static final int MAX_COORDINATE = 30_000_000;
    static final int MAX_ITEM_COUNT = 999;
    private static final int MAX_MENU_TYPE_LENGTH = 128;

    private AuditInteractionSupport() {
    }

    static boolean accepted(InteractionResult result) {
        return result != null && result.consumesAction();
    }

    static String resultCode(InteractionResult result) {
        if (result == InteractionResult.SUCCESS) {
            return "success";
        }
        if (result == InteractionResult.SUCCESS_SERVER) {
            return "success_server";
        }
        if (result == InteractionResult.CONSUME) {
            return "consume";
        }
        if (result == InteractionResult.TRY_WITH_EMPTY_HAND) {
            return "try_with_empty_hand";
        }
        if (result instanceof InteractionResult.Success) {
            return "success";
        }
        return accepted(result) ? "consumed" : null;
    }

    static String handCode(InteractionHand hand) {
        if (hand == InteractionHand.MAIN_HAND) {
            return "main_hand";
        }
        if (hand == InteractionHand.OFF_HAND) {
            return "off_hand";
        }
        return null;
    }

    static String clickTypeCode(ContainerInput input) {
        if (input == null) {
            return null;
        }
        return switch (input) {
            case PICKUP -> "pickup";
            case QUICK_MOVE -> "quick_move";
            case SWAP -> "swap";
            case CLONE -> "clone";
            case THROW -> "throw";
            case QUICK_CRAFT -> "quick_craft";
            case PICKUP_ALL -> "pickup_all";
        };
    }

    static boolean bounded(BlockPos position) {
        return position != null
                && within(position.getX())
                && within(position.getY())
                && within(position.getZ());
    }

    static BlockPos boundedPosition(Vec3 position) {
        if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.y)
                || !Double.isFinite(position.z) || position.x < -MAX_COORDINATE
                || position.x > MAX_COORDINATE || position.y < -MAX_COORDINATE
                || position.y > MAX_COORDINATE || position.z < -MAX_COORDINATE
                || position.z > MAX_COORDINATE) {
            return null;
        }
        BlockPos blockPosition = BlockPos.containing(position);
        return bounded(blockPosition) ? blockPosition : null;
    }

    static String menuType(Class<?> menuClass) {
        if (menuClass == null) {
            return "unknown";
        }
        return boundedMenuType(menuClass.getName());
    }

    static String boundedMenuType(String sourceName) {
        if (sourceName == null) {
            return "unknown";
        }
        String source = sourceName.toLowerCase(Locale.ROOT);
        StringBuilder bounded = new StringBuilder(Math.min(source.length(), MAX_MENU_TYPE_LENGTH));
        for (int index = 0; index < source.length() && bounded.length() < MAX_MENU_TYPE_LENGTH; index++) {
            char character = source.charAt(index);
            if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')
                    || character == '.' || character == ':' || character == '_' || character == '-' || character == '/') {
                bounded.append(character);
            } else {
                bounded.append('_');
            }
        }
        if (bounded.isEmpty() || !Character.isLetterOrDigit(bounded.charAt(0))) {
            return "unknown";
        }
        return bounded.toString();
    }

    static boolean fingerprintsChanged(ContainerFingerprint before, ContainerFingerprint after) {
        return before == null || after == null || !before.equals(after);
    }

    static int boundedCount(int count) {
        return Math.max(0, Math.min(MAX_ITEM_COUNT, count));
    }

    private static boolean within(int coordinate) {
        return coordinate >= -MAX_COORDINATE && coordinate <= MAX_COORDINATE;
    }

    record ContainerFingerprint(String menuType, int stateId, String carriedItemType, int carriedCount,
                                int slotId, String slotItemType, int slotItemCount, boolean slotPresent) {
        ContainerFingerprint {
            if (menuType == null || carriedItemType == null || carriedCount < 0 || slotItemCount < 0) {
                throw new IllegalArgumentException("Invalid container fingerprint");
            }
        }
    }
}
