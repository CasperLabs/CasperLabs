import { Deploy } from 'casperlabs-grpc/io/casperlabs/casper/consensus/consensus_pb';
import {
  CLType,
  CLValueInstance
} from 'casperlabs-grpc/io/casperlabs/casper/consensus/state_pb';
import JSBI from 'jsbi';
import { ByteArray } from '../index';

// Functions to convert data to protobuf Deploy.Arg

type ToValue<T> = (x: T) => CLValueInstance;

function toValue<T>(set: (value: CLValueInstance, x: T) => void): ToValue<T> {
  return (x: T) => {
    const value = new CLValueInstance();
    set(value, x);
    return value;
  };
}

export const BytesValue = toValue<ByteArray>((value, x) => {
  const innerType = new CLType();
  innerType.setSimpleType(CLType.Simple.U8);
  const t = new CLType();
  const fixedListType = new CLType.FixedList();
  fixedListType.setInner(innerType);
  fixedListType.setLen(x.length);
  t.setFixedListType(fixedListType);

  const v = new CLValueInstance.Value();
  const fixedListValue = new CLValueInstance.FixedList();
  const bytes = Array.from(x, b => {
    const byteValue = new CLValueInstance.Value();
    byteValue.setU8(b);
    return byteValue;
  });
  fixedListValue.setLength(bytes.length);
  fixedListValue.setValuesList(bytes);
  v.setFixedListValue(fixedListValue);

  value.setClType(t);
  value.setValue(v);

  return value;
});

export const BigIntValue = toValue<bigint | JSBI>((value, x) => {
  const t = new CLType();
  t.setSimpleType(CLType.Simple.U512);

  const v = new CLValueInstance.Value();
  const n = new CLValueInstance.U512();
  n.setValue(x.toString());
  v.setU512(n);

  value.setClType(t);
  value.setValue(v);

  return value;
});

export const StringValue = toValue<string>((value, x) => {
  const t = new CLType();
  t.setSimpleType(CLType.Simple.STRING);

  const v = new CLValueInstance.Value();
  v.setStrValue(x)

  value.setClType(t);
  value.setValue(v);

  return value;
});

export function Args(...args: [string, CLValueInstance][]): Deploy.Arg[] {
  return args.map(x => {
    const [name, value] = x;
    const arg = new Deploy.Arg();
    arg.setName(name);
    arg.setValue(value);
    return arg;
  });
}
