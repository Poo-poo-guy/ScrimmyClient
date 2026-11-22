package com.scrimmy.client.sound;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrushableBlock;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** Audible level events in 1.21.11. Particle-only events and jukebox stop events are ignored. */
public final class SoundLevelEvents {
    private SoundLevelEvents() {}
    public static String sound(int event, int data) {
        if (event == 2001) {
            var state = Block.stateById(data);
            return state.isAir() ? null : state.getSoundType().getBreakSound().location().toString();
        }
        if (event == 3008) {
            var block = Block.stateById(data).getBlock();
            return block instanceof BrushableBlock brushable ? brushable.getBrushCompletedSound().location().toString() : null;
        }
        // The requested eye-death level event is particle-only, so identify it honestly as an event.
        if (event == 2003) return "level_event:eye_of_ender_death";
        SoundEvent sound = switch (event) {
            case 1000 -> SoundEvents.DISPENSER_DISPENSE;
            case 1001 -> SoundEvents.DISPENSER_FAIL;
            case 1002 -> SoundEvents.DISPENSER_LAUNCH;
            case 1004 -> SoundEvents.FIREWORK_ROCKET_SHOOT;
            case 1009 -> SoundEvents.FIRE_EXTINGUISH;
            case 1015 -> SoundEvents.GHAST_WARN;
            case 1016 -> SoundEvents.GHAST_SHOOT;
            case 1017 -> SoundEvents.ENDER_DRAGON_SHOOT;
            case 1018 -> SoundEvents.BLAZE_SHOOT;
            case 1019 -> SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR;
            case 1020 -> SoundEvents.ZOMBIE_ATTACK_IRON_DOOR;
            case 1021 -> SoundEvents.ZOMBIE_BREAK_WOODEN_DOOR;
            case 1022 -> SoundEvents.WITHER_BREAK_BLOCK;
            case 1023 -> SoundEvents.WITHER_SPAWN;
            case 1024 -> SoundEvents.WITHER_SHOOT;
            case 1025 -> SoundEvents.BAT_TAKEOFF;
            case 1026 -> SoundEvents.ZOMBIE_INFECT;
            case 1027 -> SoundEvents.ZOMBIE_VILLAGER_CONVERTED;
            case 1028 -> SoundEvents.ENDER_DRAGON_DEATH;
            case 1029 -> SoundEvents.ANVIL_DESTROY;
            case 1030 -> SoundEvents.ANVIL_USE;
            case 1031 -> SoundEvents.ANVIL_LAND;
            case 1032 -> SoundEvents.PORTAL_TRAVEL;
            case 1033 -> SoundEvents.CHORUS_FLOWER_GROW;
            case 1034 -> SoundEvents.CHORUS_FLOWER_DEATH;
            case 1035 -> SoundEvents.BREWING_STAND_BREW;
            case 1038 -> SoundEvents.END_PORTAL_SPAWN;
            case 1039 -> SoundEvents.PHANTOM_BITE;
            case 1040 -> SoundEvents.ZOMBIE_CONVERTED_TO_DROWNED;
            case 1041 -> SoundEvents.HUSK_CONVERTED_TO_ZOMBIE;
            case 1042 -> SoundEvents.GRINDSTONE_USE;
            case 1043 -> SoundEvents.BOOK_PAGE_TURN;
            case 1044 -> SoundEvents.SMITHING_TABLE_USE;
            case 1045 -> SoundEvents.POINTED_DRIPSTONE_LAND;
            case 1046 -> SoundEvents.POINTED_DRIPSTONE_DRIP_LAVA_INTO_CAULDRON;
            case 1047 -> SoundEvents.POINTED_DRIPSTONE_DRIP_WATER_INTO_CAULDRON;
            case 1048 -> SoundEvents.SKELETON_CONVERTED_TO_STRAY;
            case 1049 -> SoundEvents.CRAFTER_CRAFT;
            case 1050 -> SoundEvents.CRAFTER_FAIL;
            case 1051 -> SoundEvents.WIND_CHARGE_THROW;
            case 1501 -> SoundEvents.LAVA_EXTINGUISH;
            case 1502 -> SoundEvents.REDSTONE_TORCH_BURNOUT;
            case 1503 -> SoundEvents.END_PORTAL_FRAME_FILL;
            case 1505 -> SoundEvents.BONE_MEAL_USE;
            case 2006 -> SoundEvents.DRAGON_FIREBALL_EXPLODE;
            case 3000 -> SoundEvents.END_GATEWAY_SPAWN;
            case 3001 -> SoundEvents.ENDER_DRAGON_GROWL;
            case 3003 -> SoundEvents.HONEYCOMB_WAX_ON;
            case 3006 -> SoundEvents.SCULK_BLOCK_CHARGE;
            case 3007 -> SoundEvents.SCULK_SHRIEKER_SHRIEK;
            case 3012 -> SoundEvents.TRIAL_SPAWNER_SPAWN_MOB;
            case 3013 -> SoundEvents.TRIAL_SPAWNER_DETECT_PLAYER;
            case 3014 -> SoundEvents.TRIAL_SPAWNER_EJECT_ITEM;
            case 3015 -> SoundEvents.VAULT_ACTIVATE;
            case 3016 -> SoundEvents.VAULT_DEACTIVATE;
            case 3018 -> SoundEvents.COBWEB_PLACE;
            case 3019 -> SoundEvents.TRIAL_SPAWNER_DETECT_PLAYER;
            case 3020 -> SoundEvents.TRIAL_SPAWNER_OMINOUS_ACTIVATE;
            case 3021 -> SoundEvents.TRIAL_SPAWNER_SPAWN_ITEM;
            case 1500 -> data > 0 ? SoundEvents.COMPOSTER_FILL_SUCCESS : SoundEvents.COMPOSTER_FILL;
            case 2002, 2007 -> SoundEvents.SPLASH_POTION_BREAK;
            default -> null;
        };
        if (event == 1009) sound = data == 0 ? SoundEvents.FIRE_EXTINGUISH : data == 1 ? SoundEvents.GENERIC_EXTINGUISH_FIRE : null;
        return sound == null ? null : sound.location().toString();
    }
    public static String category(int event) {
        SoundSource source = switch (event) {
            case 1015, 1016, 1017, 1018, 1019, 1020, 1021, 1022, 1023, 1024, 1026, 1027, 1028,
                    1038, 1039, 1040, 1041, 1048, 2006, 3001 -> SoundSource.HOSTILE;
            case 1004, 1025, 2002, 2007 -> SoundSource.NEUTRAL;
            case 1010 -> SoundSource.RECORDS;
            case 1032, 3008 -> SoundSource.PLAYERS;
            default -> SoundSource.BLOCKS;
        };
        return source.getName();
    }
}
