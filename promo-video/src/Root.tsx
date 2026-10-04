import React from 'react';
import {Composition, Folder} from 'remotion';
import {LinuxGeek} from './videos/linux/LinuxGeek';
import {
  LinuxHook,
  LinuxArch,
  LinuxFailover,
  LinuxCache,
  LinuxStream,
  LinuxDiag,
  LinuxKmp,
  LinuxEnd,
} from './videos/linux/scenes';
import {WindowsPractical} from './videos/windows/WindowsPractical';
import {WinHook, WinWindow, WinMedia, WinSource, WinDaily, WinEnd} from './videos/windows/scenes';
import {AndroidPractical} from './videos/android/AndroidPractical';
import {
  AndroidHook,
  AndroidNotif,
  AndroidBack,
  AndroidData,
  AndroidOffline,
  AndroidPlayer,
  AndroidEnd,
} from './videos/android/scenes';

const HD = {width: 1920, height: 1080, fps: 30};
const VERTICAL = {width: 1080, height: 1920, fps: 30};

const scene = (
  id: string,
  component: React.FC,
  durationInFrames: number,
  dims: {width: number; height: number; fps: number},
) => (
  <Composition
    id={id}
    component={component}
    width={dims.width}
    height={dims.height}
    fps={dims.fps}
    durationInFrames={durationInFrames}
  />
);

export const RemotionRoot: React.FC = () => {
  return (
    <>
      <Folder name="Linux-Geek">
        <Composition
          id="LinuxGeek"
          component={LinuxGeek}
          width={HD.width}
          height={HD.height}
          fps={HD.fps}
          durationInFrames={1800}
        />
        {scene('LinuxHook', LinuxHook, 300, HD)}
        {scene('LinuxArch', LinuxArch, 300, HD)}
        {scene('LinuxFailover', LinuxFailover, 270, HD)}
        {scene('LinuxCache', LinuxCache, 270, HD)}
        {scene('LinuxStream', LinuxStream, 300, HD)}
        {scene('LinuxDiag', LinuxDiag, 216, HD)}
        {scene('LinuxKmp', LinuxKmp, 180, HD)}
        {scene('LinuxEnd', LinuxEnd, 90, HD)}
      </Folder>

      <Folder name="Windows-Practical">
        <Composition
          id="WindowsPractical"
          component={WindowsPractical}
          width={HD.width}
          height={HD.height}
          fps={HD.fps}
          durationInFrames={1500}
        />
        {scene('WinHook', WinHook, 288, HD)}
        {scene('WinWindow', WinWindow, 312, HD)}
        {scene('WinMedia', WinMedia, 300, HD)}
        {scene('WinSource', WinSource, 288, HD)}
        {scene('WinDaily', WinDaily, 258, HD)}
        {scene('WinEnd', WinEnd, 144, HD)}
      </Folder>

      <Folder name="Android-Practical">
        <Composition
          id="AndroidPractical"
          component={AndroidPractical}
          width={VERTICAL.width}
          height={VERTICAL.height}
          fps={VERTICAL.fps}
          durationInFrames={1500}
        />
        {scene('AndroidHook', AndroidHook, 240, VERTICAL)}
        {scene('AndroidNotif', AndroidNotif, 270, VERTICAL)}
        {scene('AndroidBack', AndroidBack, 240, VERTICAL)}
        {scene('AndroidData', AndroidData, 252, VERTICAL)}
        {scene('AndroidOffline', AndroidOffline, 246, VERTICAL)}
        {scene('AndroidPlayer', AndroidPlayer, 240, VERTICAL)}
        {scene('AndroidEnd', AndroidEnd, 120, VERTICAL)}
      </Folder>
    </>
  );
};
