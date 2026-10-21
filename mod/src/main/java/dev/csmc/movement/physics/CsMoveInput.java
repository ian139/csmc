package dev.csmc.movement.physics;

/**
 * One step's player input and pose. {@code forwardMove}/{@code sideMove} are CS command values (positive forward
 * and right, typically +-450 like {@code cl_forwardspeed}/{@code cl_sidespeed}); {@code yawDegrees} uses Minecraft's
 * convention (0 faces +z, 90 faces -x). {@code maxSpeed} is the run speed cap in units/s before duck/walk modifiers
 * (held weapon max speed times attribute effects such as scoping). Hull and step sizes are in units.
 */
public record CsMoveInput(
        double forwardMove,
        double sideMove,
        boolean jump,
        boolean ducked,
        boolean walk,
        double yawDegrees,
        double maxSpeed,
        double halfWidth,
        double height,
        double stepHeight,
        double surfaceFriction) {}
