// Procedural humanoid motions for VRM normalized bones.
//
// Conventions (VRM 1.0 normalized humanoid, model faces +Z toward the camera):
//  - Every normalized bone has identity rest rotation in a T-pose, axes aligned to the world.
//  - Euler order 'XYZ' (three.js): Z is applied first, then Y, then X, all around the PARENT axes.
//    So for an arm we lower it with Z first, then swing it forward/back with X.
//  - Model's LEFT side is +X. leftUpperArm.z < 0 lowers the left arm, rightUpperArm.z > 0 lowers the right arm.
//  - upperLeg.x < 0 swings the leg forward, lowerLeg.x > 0 bends the knee (foot goes back).
//  - spine.x > 0 bends forward (bow), head.x > 0 looks down.
//  - Arm swing forward: upperArm.x < 0.
//
// A motion is a function (t, ctx) => { bones: {name:[x,y,z]}, root: {y, yaw, roll, pitch, x, z}, face: {...}, look: bool }
// Missing bones fall back to the base pose. All angles in radians.

const S = Math.sin, C = Math.cos, PI = Math.PI;
const ARM_DOWN = 1.22; // lower the arms from T-pose to a relaxed stance

export const BONES = [
  'hips', 'spine', 'chest', 'upperChest', 'neck', 'head',
  'leftShoulder', 'leftUpperArm', 'leftLowerArm', 'leftHand',
  'rightShoulder', 'rightUpperArm', 'rightLowerArm', 'rightHand',
  'leftUpperLeg', 'leftLowerLeg', 'leftFoot',
  'rightUpperLeg', 'rightLowerLeg', 'rightFoot',
];

export function basePose(t, ctx) {
  // Relaxed idle: breathing, gentle weight shift, tiny head motion.
  const b = S(t * 1.6); // breathing
  const sway = S(t * 0.55);
  const e = ctx.energy ?? 1;
  return {
    bones: {
      spine: [0.02 + b * 0.012 * e, 0, sway * 0.015],
      chest: [b * 0.018 * e, 0, 0],
      neck: [0, 0, 0],
      head: [S(t * 0.7) * 0.02, S(t * 0.37) * 0.04, sway * -0.02],
      leftShoulder: [0, 0, -b * 0.02],
      rightShoulder: [0, 0, b * 0.02],
      leftUpperArm: [0.04, 0, -ARM_DOWN + b * 0.025],
      rightUpperArm: [0.04, 0, ARM_DOWN - b * 0.025],
      leftLowerArm: [0, -0.18, 0],
      rightLowerArm: [0, 0.18, 0],
      leftHand: [0, 0, -0.1],
      rightHand: [0, 0, 0.1],
      leftUpperLeg: [0, 0, 0.015 - sway * 0.01],
      rightUpperLeg: [0, 0, -0.015 - sway * 0.01],
      leftLowerLeg: [0, 0, 0],
      rightLowerLeg: [0, 0, 0],
      hips: [0, 0, sway * 0.01],
    },
    root: { y: 0, yaw: 0 },
  };
}

function walkCycle(t, speed, ctx) {
  const f = 2 * PI * speed; // steps per second / 2
  const p = t * f;
  const s = S(p);
  const leg = 0.42;
  const knee = (x) => Math.max(0, x) * 0.85 + 0.08;
  return {
    bones: {
      hips: [0, S(p) * 0.08, 0],
      spine: [0.06, -S(p) * 0.1, 0],
      chest: [0.02, -S(p) * 0.05, 0],
      head: [0.02, S(p) * 0.12, 0],
      leftUpperLeg: [-s * leg, 0, 0.02],
      rightUpperLeg: [s * leg, 0, -0.02],
      leftLowerLeg: [knee(S(p - 1.4)), 0, 0],
      rightLowerLeg: [knee(S(p + PI - 1.4)), 0, 0],
      leftFoot: [s * 0.15, 0, 0],
      rightFoot: [-s * 0.15, 0, 0],
      leftUpperArm: [s * 0.45, 0, -ARM_DOWN + 0.05],
      rightUpperArm: [-s * 0.45, 0, ARM_DOWN - 0.05],
      leftLowerArm: [0, -0.35 - Math.max(0, s) * 0.3, 0],
      rightLowerArm: [0, 0.35 + Math.max(0, -s) * 0.3, 0],
    },
    root: { y: Math.abs(C(p)) * 0.035 - 0.012, yaw: ctx.facingYaw ?? 0 },
  };
}

// Sitting (on the living-room sofa). ctx.sitDrop = how far the hips drop so that the thighs rest at knee height.
function sitPose(ctx, extra = {}) {
  return {
    leftUpperLeg: [-1.45, 0, 0.08], rightUpperLeg: [-1.45, 0, -0.08],
    leftLowerLeg: [1.45, 0, 0], rightLowerLeg: [1.45, 0, 0],
    leftFoot: [0.05, 0, 0], rightFoot: [0.05, 0, 0],
    leftUpperArm: [-0.35, 0, -1.1], rightUpperArm: [-0.35, 0, 1.1],
    leftLowerArm: [0, -0.55, 0], rightLowerArm: [0, 0.55, 0],
    ...extra,
  };
}

export const MOTIONS = {
  sit: {
    loop: true,
    fn: (t, ctx) => ({
      bones: sitPose(ctx, { spine: [-0.05 + Math.sin(t * 1.6) * 0.01, 0, 0], head: [0.02, Math.sin(t * 0.4) * 0.15, 0] }),
      root: { y: ctx.sitDrop ?? -0.35 },
      look: true,
    }),
  },
  // Dozing on the sofa (family member's phone says they are asleep).
  sleepSit: {
    loop: true,
    fn: (t, ctx) => {
      const b = (Math.sin(t * 1.1) + 1) / 2;
      return {
        bones: sitPose(ctx, { spine: [0.18, 0, 0.05], chest: [0.08, 0, 0], neck: [0.15, 0, 0], head: [0.35 + b * 0.06, 0.1, 0.28] }),
        root: { y: ctx.sitDrop ?? -0.35 },
        face: { blink: 1, relaxed: 0.6 },
        energy: 0.3,
      };
    },
  },
  // Stretch / yawn (idle variety in the living room).
  stretch: {
    duration: 2.6,
    fn: (t) => {
      const k = Math.sin(Math.min(1, t / 2.6) * PI);
      return {
        bones: {
          leftUpperArm: [0, 0, -ARM_DOWN + 2.3 * k], rightUpperArm: [0, 0, ARM_DOWN - 2.3 * k],
          leftLowerArm: [0, 0, 0.3 * k], rightLowerArm: [0, 0, -0.3 * k],
          spine: [-0.12 * k, 0, 0], head: [-0.25 * k, 0, 0],
        },
        face: { relaxed: k, aa: k * 0.6 },
      };
    },
  },
  lookAround: {
    duration: 3,
    fn: (t) => ({ bones: { head: [0.05, Math.sin(t * 2.1) * 0.6, 0], neck: [0, Math.sin(t * 2.1) * 0.2, 0] } }),
  },
  idle: {
    loop: true,
    fn: () => ({ bones: {}, root: {} }),
  },

  // Walk in place (native side moves the window, or the stage moves the actor).
  walk: {
    loop: true,
    fn: (t, ctx) => walkCycle(t, 0.95, ctx),
  },
  run: {
    loop: true,
    fn: (t, ctx) => {
      const m = walkCycle(t, 1.6, ctx);
      m.bones.spine[0] = 0.18;
      m.bones.leftLowerArm[1] = -1.2;
      m.bones.rightLowerArm[1] = 1.2;
      return m;
    },
  },

  wave: {
    duration: 2.6,
    fn: (t) => ({
      bones: {
        rightUpperArm: [0, 0.25, -0.25],
        rightLowerArm: [0, 0, -1.45 + S(t * 11) * 0.4],
        rightHand: [0, 0, -0.2],
        head: [0, -0.05, 0.12],
        spine: [0, 0, 0.04],
      },
      face: { happy: 0.7 },
      look: true,
    }),
  },

  bothWave: {
    duration: 2.6,
    fn: (t) => ({
      bones: {
        rightUpperArm: [0, 0.25, -0.25],
        rightLowerArm: [0, 0, -1.45 + S(t * 11) * 0.4],
        leftUpperArm: [0, -0.25, 0.25],
        leftLowerArm: [0, 0, 1.45 - S(t * 11 + 1) * 0.4],
        head: [0, 0, S(t * 5) * 0.08],
      },
      root: { y: Math.abs(S(t * 5.5)) * 0.03 },
      face: { happy: 1 },
      look: true,
    }),
  },

  nod: {
    duration: 1.4,
    fn: (t) => ({
      bones: { head: [Math.max(0, S(t * 9)) * 0.28 * Math.max(0, 1 - t / 1.4), 0, 0], neck: [0.05, 0, 0] },
      face: { happy: 0.4 },
      look: true,
    }),
  },

  bow: {
    duration: 2.4,
    fn: (t) => {
      const k = t < 0.5 ? t / 0.5 : t < 1.6 ? 1 : Math.max(0, 1 - (t - 1.6) / 0.8);
      const e = k * k * (3 - 2 * k);
      return {
        bones: {
          spine: [0.45 * e, 0, 0], chest: [0.2 * e, 0, 0], head: [0.2 * e, 0, 0],
          leftUpperArm: [-0.1 * e, 0, -ARM_DOWN - 0.12 * e], rightUpperArm: [-0.1 * e, 0, ARM_DOWN + 0.12 * e],
          leftLowerArm: [0, -0.4 * e, 0], rightLowerArm: [0, 0.4 * e, 0],
        },
        face: { relaxed: 0.8 * e },
      };
    },
  },

  jump: {
    duration: 1.2,
    fn: (t) => {
      const air = t > 0.25 && t < 0.85 ? S(((t - 0.25) / 0.6) * PI) : 0;
      const crouch = t < 0.25 ? S((t / 0.25) * PI) : t > 0.85 ? S(((t - 0.85) / 0.35) * PI) * 0.7 : 0;
      return {
        bones: {
          leftUpperLeg: [-0.5 * crouch - 0.2 * air, 0, 0.05], rightUpperLeg: [-0.5 * crouch - 0.2 * air, 0, -0.05],
          leftLowerLeg: [0.9 * crouch + 0.5 * air, 0, 0], rightLowerLeg: [0.9 * crouch + 0.5 * air, 0, 0],
          leftFoot: [-0.4 * crouch, 0, 0], rightFoot: [-0.4 * crouch, 0, 0],
          spine: [0.15 * crouch - 0.05 * air, 0, 0],
          leftUpperArm: [0, 0, -ARM_DOWN + 1.9 * air], rightUpperArm: [0, 0, ARM_DOWN - 1.9 * air],
        },
        root: { y: air * 0.22 - crouch * 0.08 },
        face: { happy: 0.9, aa: air * 0.5 },
      };
    },
  },

  cheer: {
    duration: 3.2,
    fn: (t) => {
      const ph = (t * 2.2) % 1;
      const air = ph > 0.3 ? S(((ph - 0.3) / 0.7) * PI) : 0;
      const crouch = ph <= 0.3 ? S((ph / 0.3) * PI) : 0;
      return {
        bones: {
          leftUpperArm: [0, 0, 0.35 + S(t * 13) * 0.12], rightUpperArm: [0, 0, -0.35 - S(t * 13) * 0.12],
          leftLowerArm: [0, 0, 0.3], rightLowerArm: [0, 0, -0.3],
          leftUpperLeg: [-0.4 * crouch, 0, 0.05], rightUpperLeg: [-0.4 * crouch, 0, -0.05],
          leftLowerLeg: [0.8 * crouch, 0, 0], rightLowerLeg: [0.8 * crouch, 0, 0],
          head: [-0.1, 0, 0],
        },
        root: { y: air * 0.12 - crouch * 0.06 },
        face: { happy: 1, aa: 0.4 },
        look: true,
      };
    },
  },

  dance: {
    loop: true,
    fn: (t) => {
      const beat = t * 2 * PI * 1.1;
      const bounce = Math.abs(S(beat));
      const part = Math.floor(t / 2.9) % 3;
      const armL = part === 0 ? [0, 0, 0.3 + S(beat) * 0.4] : part === 1 ? [-1.1, 0, -0.6] : [0, 0, -ARM_DOWN + 0.3];
      const armR = part === 0 ? [0, 0, -0.3 + S(beat) * 0.4] : part === 1 ? [-1.1, 0, 0.6] : [0, 0, -0.3 - S(beat) * 0.5];
      return {
        bones: {
          hips: [0, S(beat / 2) * 0.25, S(beat) * 0.08],
          spine: [0, -S(beat / 2) * 0.15, -S(beat) * 0.1],
          head: [0, S(beat / 2) * 0.1, S(beat) * 0.15],
          leftUpperArm: armL, rightUpperArm: armR,
          leftLowerArm: [0, -0.3, part === 1 ? -1.3 : 0.4], rightLowerArm: [0, 0.3, part === 1 ? 1.3 : -0.4],
          leftUpperLeg: [-bounce * 0.25, 0, 0.1], rightUpperLeg: [-(1 - bounce) * 0.25, 0, -0.1],
          leftLowerLeg: [bounce * 0.5, 0, 0], rightLowerLeg: [(1 - bounce) * 0.5, 0, 0],
        },
        root: { y: bounce * 0.04, yaw: part === 2 ? S(beat / 4) * 0.6 : 0 },
        face: { happy: 0.85 },
        look: true,
      };
    },
  },

  sad: {
    loop: true,
    fn: (t) => ({
      bones: {
        spine: [0.12, 0, 0], chest: [0.08, 0, 0], neck: [0.1, 0, 0], head: [0.25 + S(t * 0.8) * 0.03, 0, 0.08],
        leftShoulder: [0, 0, 0.08], rightShoulder: [0, 0, -0.08],
        leftUpperArm: [0.1, 0, -ARM_DOWN - 0.12], rightUpperArm: [0.1, 0, ARM_DOWN + 0.12],
        leftLowerArm: [0, -0.1, 0], rightLowerArm: [0, 0.1, 0],
      },
      face: { sad: 0.9 },
      energy: 0.4,
    }),
  },

  angry: {
    duration: 2.6,
    fn: (t) => {
      const stomp = Math.max(0, S(t * 9));
      return {
        bones: {
          // fists on the hips
          leftUpperArm: [0.2, 0.5, -0.95], rightUpperArm: [0.2, -0.5, 0.95],
          leftLowerArm: [0, -1.9, 0], rightLowerArm: [0, 1.9, 0],
          rightUpperLeg: [-stomp * 0.35, 0, -0.03], rightLowerLeg: [stomp * 0.5, 0, 0],
          spine: [-0.04, 0, 0], head: [0.06, 0, S(t * 9) * 0.05],
        },
        root: { y: -stomp * 0.01 },
        face: { angry: 1 },
        look: true,
      };
    },
  },

  surprised: {
    duration: 1.6,
    fn: (t) => {
      const k = Math.min(1, t / 0.15) * Math.max(0, Math.min(1, (1.6 - t) / 0.5));
      return {
        bones: {
          spine: [-0.12 * k, 0, 0], head: [-0.12 * k, 0, 0],
          leftUpperArm: [-0.3 * k, 0, -ARM_DOWN + 0.85 * k], rightUpperArm: [-0.3 * k, 0, ARM_DOWN - 0.85 * k],
          leftLowerArm: [0, -1.1 * k, 0], rightLowerArm: [0, 1.1 * k, 0],
        },
        root: { y: S(Math.min(1, t / 0.35) * PI) * 0.06 },
        face: { surprised: 1 },
        look: true,
      };
    },
  },

  think: {
    loop: true,
    fn: (t) => ({
      bones: {
        rightUpperArm: [-0.55, 0, 1.0], rightLowerArm: [0, 2.2, 0], rightHand: [0, 0, 0.2],
        leftUpperArm: [0, -0.4, -1.0], leftLowerArm: [0, -1.5, 0],
        head: [0.05, 0.12, 0.18 + S(t * 1.2) * 0.04],
      },
      face: { relaxed: 0.25, ou: 0.2 },
    }),
  },

  sleepy: {
    loop: true,
    fn: (t) => {
      const doze = (S(t * 0.9) + 1) / 2;
      return {
        bones: {
          head: [0.25 + doze * 0.15, 0, 0.2 * doze], neck: [0.1, 0, 0],
          spine: [0.08, 0, 0],
          leftUpperArm: [0.05, 0, -ARM_DOWN - 0.1], rightUpperArm: [0.05, 0, ARM_DOWN + 0.1],
        },
        face: { relaxed: 0.6, blink: 0.7 + doze * 0.3 },
        energy: 0.3,
      };
    },
  },

  // Knock on the "glass" of the screen: reminder for unread messages.
  knock: {
    duration: 2.4,
    fn: (t) => {
      const k = Math.max(0, S(t * 14));
      return {
        bones: {
          rightUpperArm: [-0.85, 0, 1.0], rightLowerArm: [0, 1.95 - k * 0.45, 0], rightHand: [0.3, 0, 0],
          spine: [0.06, 0, 0], head: [-0.05, 0, 0.06],
        },
        face: { surprised: 0.3, aa: k * 0.3 },
        look: true,
      };
    },
  },

  point: {
    duration: 2.2,
    fn: () => ({
      bones: { rightUpperArm: [0, 1.2, 0.05], rightLowerArm: [0, 0.12, 0], head: [0, -0.15, 0] },
      face: { happy: 0.3 },
      look: true,
    }),
  },

  // Held by the user's finger: limbs dangle.
  carried: {
    loop: true,
    fn: (t, ctx) => {
      const vx = Math.max(-1, Math.min(1, (ctx.velX ?? 0) / 2000));
      const w = S(t * 7) * 0.15;
      return {
        bones: {
          leftUpperArm: [0, 0, 0.75 + w], rightUpperArm: [0, 0, -0.75 + w],
          leftLowerArm: [0, 0, 0.35], rightLowerArm: [0, 0, -0.35],
          leftUpperLeg: [0.2 + w, 0, 0.1], rightUpperLeg: [-0.1 - w, 0, -0.1],
          leftLowerLeg: [0.6, 0, 0], rightLowerLeg: [0.4, 0, 0],
          spine: [0, 0, -vx * 0.3], head: [-0.1, 0, vx * 0.2],
        },
        root: { roll: -vx * 0.35 },
        face: { surprised: 0.8 },
      };
    },
  },

  thrown: {
    loop: true,
    fn: (t) => ({
      bones: {
        leftUpperArm: [S(t * 25) * 0.4, 0, 0.2], rightUpperArm: [-S(t * 25) * 0.4, 0, -0.2],
        leftUpperLeg: [S(t * 20) * 0.5, 0, 0.1], rightUpperLeg: [-S(t * 20) * 0.5, 0, -0.1],
        leftLowerLeg: [0.5, 0, 0], rightLowerLeg: [0.5, 0, 0],
      },
      root: { roll: t * 9 },
      face: { surprised: 1, aa: 0.6 },
    }),
  },

  dizzy: {
    duration: 2.8,
    fn: (t) => ({
      bones: {
        head: [S(t * 6) * 0.15, 0, C(t * 6) * 0.2], spine: [S(t * 6) * 0.06, 0, C(t * 6) * 0.08],
        leftUpperArm: [0, 0, -ARM_DOWN + 0.4], rightUpperArm: [0, 0, ARM_DOWN - 0.4],
      },
      root: { roll: C(t * 6) * 0.06 },
      face: { surprised: 0.4, sad: 0.3 },
    }),
  },

  // Turn away with a pout (after being thrown away).
  pout: {
    duration: 2.6,
    fn: (t) => ({
      bones: {
        leftUpperArm: [0.2, 0.5, -0.95], rightUpperArm: [0.2, -0.5, 0.95],
        leftLowerArm: [0, -1.9, 0], rightLowerArm: [0, 1.9, 0],
        head: [-0.12, 0.35, -0.1],
      },
      root: { yaw: -0.55 * Math.min(1, t * 3) },
      face: { angry: 0.55, ou: 0.4 },
    }),
  },

  // Peek in from the edge of the screen.
  peek: {
    loop: true,
    fn: (t) => ({
      bones: { spine: [0, 0, -0.25], chest: [0, 0, -0.1], head: [0, 0, -0.25 + S(t * 2) * 0.05] },
      face: { happy: 0.3 },
      look: true,
    }),
  },

  // Hands together "お願い" / thank-you.
  pray: {
    duration: 2.2,
    fn: () => ({
      bones: {
        leftUpperArm: [-0.4, -0.5, -1.1], rightUpperArm: [-0.4, 0.5, 1.1],
        leftLowerArm: [0, -1.75, 0], rightLowerArm: [0, 1.75, 0],
        spine: [0.12, 0, 0], head: [0.2, 0, 0.1],
      },
      face: { happy: 0.6 },
    }),
  },

  // Talking gesture: small hand movements while speaking.
  talk: {
    loop: true,
    fn: (t) => ({
      bones: {
        rightUpperArm: [-0.3, 0, 1.0 + S(t * 3) * 0.06], rightLowerArm: [0, 1.2 + S(t * 4.2) * 0.25, 0],
        leftUpperArm: [-0.15, 0, -1.1], leftLowerArm: [0, -0.8 - S(t * 3.3 + 1) * 0.2, 0],
        head: [S(t * 2.1) * 0.05, S(t * 1.3) * 0.08, S(t * 1.7) * 0.05],
      },
      look: true,
    }),
  },

  // Landing after a throw.
  land: {
    duration: 0.8,
    fn: (t) => {
      const k = S(Math.min(1, t / 0.8) * PI);
      return {
        bones: {
          leftUpperLeg: [-0.6 * k, 0, 0.1], rightUpperLeg: [-0.6 * k, 0, -0.1],
          leftLowerLeg: [1.1 * k, 0, 0], rightLowerLeg: [1.1 * k, 0, 0],
          leftFoot: [-0.5 * k, 0, 0], rightFoot: [-0.5 * k, 0, 0],
          spine: [0.3 * k, 0, 0],
          leftUpperArm: [0, 0, -ARM_DOWN + 0.6 * k], rightUpperArm: [0, 0, ARM_DOWN - 0.6 * k],
        },
        root: { y: -0.13 * k },
        face: { surprised: 0.6 },
      };
    },
  },
};

export const MOTION_NAMES = Object.keys(MOTIONS);
