export interface LocationData {
  x: number;
  y: number;
  z: number;
  yaw?: number;
  pitch?: number;
  world?: string;
}

export class Location {
  constructor(
    public readonly x: number,
    public readonly y: number,
    public readonly z: number,
    public readonly yaw: number = 0,
    public readonly pitch: number = 0,
    public readonly world?: string,
  ) {}

  static from(raw: LocationData): Location {
    return new Location(raw.x, raw.y, raw.z, raw.yaw ?? 0, raw.pitch ?? 0, raw.world);
  }

  toObject(): LocationData {
    return { x: this.x, y: this.y, z: this.z, yaw: this.yaw, pitch: this.pitch, world: this.world };
  }
}
